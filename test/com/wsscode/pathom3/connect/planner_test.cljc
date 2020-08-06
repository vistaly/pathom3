(ns com.wsscode.pathom3.connect.planner-test
  (:require
    #?(:clj [clojure.java.io :as io])
    [clojure.test :refer [deftest is are run-tests testing]]
    [clojure.walk :as walk]
    [com.wsscode.misc.core :as misc]
    [com.wsscode.pathom3.connect.foreign :as p.c.f]
    [com.wsscode.pathom3.connect.indexes :as pci]
    [com.wsscode.pathom3.connect.operation :as pco]
    [com.wsscode.pathom3.connect.planner :as pcp]
    [com.wsscode.pathom3.specs :as pspec]
    [edn-query-language.core :as eql]
    #?(:clj [tangle.core :as tangle])))

(defn register-index [resolvers]
  (let [resolvers (walk/postwalk
                    (fn [x]
                      (if (and (map? x) (contains? x ::pco/output))
                        (pco/resolver (assoc x ::pco/resolve (fn [_ _])))
                        x))
                    resolvers)]
    (pci/register {} resolvers)))

(comment
  (register-index
    [{::pco/name   'a
      ::pco/output [:a]}
     {::pco/name   'b
      ::pco/input  [:a]
      ::pco/output [:b]}]))

(defn oir-index [resolvers]
  (::pci/index-oir (register-index resolvers)))

(defn base-graph-env []
  (pcp/base-env))

#?(:clj
   (defn find-next-file-name [prefix]
     (loop [n         1
            file-name (str prefix ".png")]
       (let [file (io/file file-name)]
         (if (.exists file)
           (recur
             (inc n)
             (str prefix "-" n ".png"))
           file-name)))))

#?(:clj
   (defn plan->dot [env {::pcp/keys [nodes root]}]
     (let [edges (into []
                       (mapcat
                         (fn [{::pcp/keys [run-next node-id] :as node}]
                           (let [branches (pcp/node-branches node)]
                             (cond-> (into []
                                           (map #(vector node-id % {:color "orange"}))
                                           branches)
                               run-next
                               (conj [node-id run-next])))))
                       (vals nodes))]
       (tangle/graph->dot (mapv ::pcp/node-id (vals nodes)) edges
                          {:graph            {:rankdir :LR}
                           :node             {:shape :circle}
                           :directed?        true
                           :node->id         identity
                           :node->descriptor (fn [node-id]
                                               (let [node (get nodes node-id)]
                                                 (cond-> {:id    (str node-id)
                                                          :style "filled"
                                                          :color (if (= node-id root) "blue" "#F3F3F3")
                                                          :label (str
                                                                   (str node-id " | ")
                                                                   #_(if attrs
                                                      (str (str/join "" attrs) " | "))
                                                                   (pcp/node->label node))}
                                                   (pcp/dynamic-resolver? env (::pco/name node))
                                                   (assoc
                                                     :fontcolor "white"
                                                     :fillcolor "black")

                                                   (::pcp/run-and node)
                                                   (assoc
                                                     :fillcolor "yellow")

                                                   (::pcp/run-or node)
                                                   (assoc
                                                     :fillcolor "cyan"))))}))))

(defn render-graph [_graph _env]
  #?(:clj
     (let [graph _graph
           {::keys [file-name] :as env} _env]
       (if (not (System/getenv "PATHOM_TEST"))
         (let [dot (plan->dot env graph)]
           (io/copy (tangle/dot->svg dot) (io/file (or file-name "out.svg")))
           #_(io/copy (tangle/dot->image dot "png") (io/file (or file-name "out.png")))))))
  _graph)

#?(:clj
   (defn render-graph-next [graph env]
     (render-graph graph (assoc env ::file-name (find-next-file-name "out")))))

(defn compute-run-graph* [{::keys [out env]}]
  (pcp/compute-run-graph
    out
    env))

(defn compute-run-graph
  [{::keys     [resolvers render-graphviz? time? dynamics]
    ::eql/keys [query]
    :or        {render-graphviz? false
                time?            false}
    :as        options}]
  (let [env     (cond-> (merge (base-graph-env)
                               (-> options
                                   (dissoc ::eql/query)
                                   (assoc :edn-query-language.ast/node
                                     (eql/query->ast query))
                                   (cond->
                                     (::pci/index-resolvers options)
                                     (update ::pci/index-resolvers
                                       #(misc/map-vals pco/resolver %)))))
                  resolvers
                  (pci/merge-indexes (register-index resolvers))

                  dynamics
                  (as-> <>
                    (reduce
                      (fn [env' [name resolvers]]
                        (pci/merge-indexes env'
                          (p.c.f/internalize-parser-index*
                            (assoc (register-index resolvers) ::pci/index-source-id name))))
                      <>
                      dynamics)))
        options (assoc options ::env env)]
    (cond->
      (if time?
        (time (compute-run-graph* options))
        (compute-run-graph* options))

      render-graphviz?
      (render-graph env))))

(deftest compute-run-graph-test-no-path
  (testing "no path"
    (is (= (compute-run-graph
             {::pci/index-oir '{}
              ::eql/query     [:a]})
           {::pcp/nodes             {}
            ::pcp/index-syms        {}
            ::pcp/unreachable-attrs #{:a}
            ::pcp/unreachable-syms  #{}}))

    (testing "ignore mutations"
      (is (= (compute-run-graph
               {::pci/index-oir '{}
                ::eql/query     '[(foo {})]})
             {::pcp/nodes             {}
              ::pcp/index-syms        {}
              ::pcp/unreachable-syms  #{}
              ::pcp/unreachable-attrs #{}})))

    (testing "broken chain"
      (is (= (compute-run-graph
               {::pci/index-oir '{:b {#{:a} #{b}}}
                ::eql/query     [:b]})
             '#::pcp{:nodes             {}
                     :index-syms        {}
                     :unreachable-attrs #{:a :b}
                     :unreachable-syms  #{b}}))

      (is (= (compute-run-graph
               {::pci/index-oir '{:b {#{:a} #{b1 b}}}
                ::eql/query     [:b]})
             '#::pcp{:nodes             {}
                     :index-syms        {}
                     :unreachable-attrs #{:a :b}
                     :unreachable-syms  #{b b1}}))

      (is (= (compute-run-graph
               {::resolvers [{::pco/name   'a
                              ::pco/output [:a]}
                             {::pco/name   'b
                              ::pco/input  [:a]
                              ::pco/output [:b]}]
                ::eql/query [:b]
                ::out       {::pcp/unreachable-attrs #{:a}}})
             '#::pcp{:nodes             {}
                     :index-syms        {}
                     :unreachable-attrs #{:a :b}
                     :unreachable-syms  #{b}}))

      (is (= (compute-run-graph
               {::resolvers [{::pco/name   'b
                              ::pco/input  [:a]
                              ::pco/output [:b]}
                             {::pco/name   'c
                              ::pco/input  [:b]
                              ::pco/output [:c]}]
                ::eql/query [:c]})
             '#::pcp{:nodes             {}
                     :index-syms        {}
                     :unreachable-attrs #{:a :b :c}
                     :unreachable-syms  #{b c}}))

      (is (= (compute-run-graph
               {::resolvers [{::pco/name   'b
                              ::pco/input  [:a]
                              ::pco/output [:b]}
                             {::pco/name   'd
                              ::pco/output [:d]}
                             {::pco/name   'c
                              ::pco/input  [:b :d]
                              ::pco/output [:c]}]
                ::eql/query [:c]})
             '#::pcp{:nodes             {}
                     :index-syms        {}
                     :unreachable-attrs #{:a :b :c}
                     :unreachable-syms  #{b c}}))

      (is (= (compute-run-graph
               {::resolvers [{::pco/name   'b
                              ::pco/input  [:a]
                              ::pco/output [:b]}
                             {::pco/name   'd
                              ::pco/output [:d]}
                             {::pco/name   'c
                              ::pco/input  [:b :d]
                              ::pco/output [:c]}]
                ::eql/query [:c :d]})
             '{::pcp/nodes             {4 {::pco/name             d
                                           ::pcp/node-id          4
                                           ::pcp/requires         {:d {}}
                                           ::pcp/input            {}
                                           ::pcp/source-for-attrs #{:d}}}
               ::pcp/index-syms        {d #{4}}
               ::pcp/unreachable-syms  #{c b}
               ::pcp/unreachable-attrs #{:c :b :a}
               ::pcp/root              4
               ::pcp/index-attrs       {:d 4}})))))

(deftest compute-run-graph-test
  (testing "simplest path"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}]
              ::eql/query [:a]})
           '{::pcp/nodes             {1 {::pco/name             a
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:a}}}
             ::pcp/index-syms        {a #{1}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/root              1
             ::pcp/index-attrs       {:a 1}})))

  (testing "ignore idents"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}]
              ::eql/query [:a [:foo "bar"]]})
           '{::pcp/nodes             {1 {::pco/name             a
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:a}}}
             ::pcp/index-syms        {a #{1}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/root              1
             ::pcp/index-attrs       {:a 1}})))

  (testing "cycles"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/input  [:b]
                            ::pco/output [:a]}
                           {::pco/name   'b
                            ::pco/input  [:a]
                            ::pco/output [:b]}]
              ::eql/query [:a]})
           '#::pcp{:nodes             {}
                   :index-syms        {}
                   :unreachable-attrs #{:b :a}
                   :unreachable-syms  #{a b}}))

    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/input  [:c]
                            ::pco/output [:a]}
                           {::pco/name   'b
                            ::pco/input  [:a]
                            ::pco/output [:b]}
                           {::pco/name   'c
                            ::pco/input  [:b]
                            ::pco/output [:c]}]
              ::eql/query [:a]})
           '#::pcp{:nodes             {}
                   :index-syms        {}
                   :unreachable-attrs #{:c :b :a}
                   :unreachable-syms  #{a b c}}))

    (testing "partial cycle"
      (is (= (compute-run-graph
               {::pci/index-oir '{:a {#{:c} #{a}
                                      #{}   #{a1}}
                                  :b {#{:a} #{b}}
                                  :c {#{:b} #{c}}
                                  :d {#{} #{d}}}
                ::eql/query     [:c :a]})
             '{::pcp/nodes             {1 {::pco/name             c
                                           ::pcp/node-id          1
                                           ::pcp/requires         {:c {}}
                                           ::pcp/input            {:b {}}
                                           ::pcp/after-nodes      #{2}
                                           ::pcp/source-for-attrs #{:c}}
                                        2 {::pco/name             b
                                           ::pcp/node-id          2
                                           ::pcp/requires         {:b {}}
                                           ::pcp/input            {:a {}}
                                           ::pcp/run-next         1
                                           ::pcp/after-nodes      #{4}
                                           ::pcp/source-for-attrs #{:b}}
                                        4 {::pco/name             a1
                                           ::pcp/node-id          4
                                           ::pcp/requires         {:a {}}
                                           ::pcp/input            {}
                                           ::pcp/run-next         2
                                           ::pcp/source-for-attrs #{:a}}}
               ::pcp/index-syms        {c #{1} b #{2} a1 #{4}}
               ::pcp/unreachable-syms  #{a}
               ::pcp/unreachable-attrs #{}
               ::pcp/root              4
               ::pcp/index-attrs       {:a 4 :b 2 :c 1}}))))

  (testing "collapse nodes"
    (is (= (compute-run-graph
             {::pci/index-oir '{:a {#{} #{a}}
                                :b {#{} #{a}}
                                :c {#{} #{a}}}
              ::eql/query     [:a :b]})
           '{::pcp/nodes             {1 {::pco/name             a
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:a {} :b {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:a :b}}}
             ::pcp/index-syms        {a #{1}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:a 1 :b 1}
             ::pcp/root              1}))

    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a :c]}
                           {::pco/name   'b
                            ::pco/output [:b]}]
              ::eql/query [:a :b :c]})
           '{::pcp/nodes             {1 {::pco/name             a
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:a {} :c {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:c :a}
                                         ::pcp/after-nodes      #{3}}
                                      2 {::pco/name             b
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:b}
                                         ::pcp/after-nodes      #{3}}
                                      3 {::pcp/node-id  3
                                         ::pcp/requires {:b {} :a {} :c {}}
                                         ::pcp/run-and  #{2 1}}}
             ::pcp/index-syms        {a #{1} b #{2}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:a 1 :b 2 :c 1}
             ::pcp/root              3})))

  (testing "OR on multiple paths"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}
                           {::pco/name   'a2
                            ::pco/output [:a]}]
              ::eql/query [:a]})
           '{::pcp/nodes             {1 {::pco/name        a
                                         ::pcp/node-id     1
                                         ::pcp/requires    {:a {}}
                                         ::pcp/input       {}
                                         ::pcp/after-nodes #{3}}
                                      2 {::pco/name        a2
                                         ::pcp/node-id     2
                                         ::pcp/requires    {:a {}}
                                         ::pcp/input       {}
                                         ::pcp/after-nodes #{3}}
                                      3 {::pcp/node-id          3
                                         ::pcp/requires         {:a {}}
                                         ::pcp/run-or           #{1 2}
                                         ::pcp/source-for-attrs #{:a}}}
             ::pcp/index-syms        {a #{1} a2 #{2}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/root              3
             ::pcp/index-attrs       {:a 3}})))

  (testing "AND on multiple attributes"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}
                           {::pco/name   'b
                            ::pco/output [:b]}]
              ::eql/query [:a :b]})
           '{::pcp/nodes             {1 {::pco/name             a
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:a}
                                         ::pcp/after-nodes      #{3}}
                                      2 {::pco/name             b
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:b}
                                         ::pcp/after-nodes      #{3}}
                                      3 {::pcp/node-id  3
                                         ::pcp/requires {:b {} :a {}}
                                         ::pcp/run-and  #{2 1}}}
             ::pcp/index-syms        {a #{1} b #{2}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:a 1 :b 2}
             ::pcp/root              3}))

    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}
                           {::pco/name   'b
                            ::pco/output [:b]}
                           {::pco/name   'c
                            ::pco/output [:c]}]
              ::eql/query [:a :b :c]})
           '{::pcp/nodes             {1 {::pco/name             a
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:a}
                                         ::pcp/after-nodes      #{3}}
                                      2 {::pco/name             b
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:b}
                                         ::pcp/after-nodes      #{3}}
                                      3 {::pcp/node-id  3
                                         ::pcp/requires {:b {} :a {} :c {}}
                                         ::pcp/run-and  #{2 1 4}}
                                      4 {::pco/name             c
                                         ::pcp/node-id          4
                                         ::pcp/requires         {:c {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:c}
                                         ::pcp/after-nodes      #{3}}}
             ::pcp/index-syms        {a #{1} b #{2} c #{4}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:a 1 :b 2 :c 4}
             ::pcp/root              3})))

  (testing "requires one nested nodes"
    (is (= (compute-run-graph
             '{::pci/index-oir {:multi    {#{:direct :indirect} #{multi}}
                                :direct   {#{} #{direct}}
                                :indirect {#{:dep} #{indirect}}
                                :dep      {#{} #{dep}}}
               ::eql/query     [:multi]})
           '{::pcp/nodes             {1 {::pco/name             multi
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:multi {}}
                                         ::pcp/input            {:direct {} :indirect {}}
                                         ::pcp/after-nodes      #{5}
                                         ::pcp/source-for-attrs #{:multi}}
                                      2 {::pco/name             direct
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:direct {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:direct}
                                         ::pcp/after-nodes      #{5}}
                                      3 {::pco/name             indirect
                                         ::pcp/node-id          3
                                         ::pcp/requires         {:indirect {}}
                                         ::pcp/input            {:dep {}}
                                         ::pcp/after-nodes      #{4}
                                         ::pcp/source-for-attrs #{:indirect}}
                                      4 {::pco/name             dep
                                         ::pcp/node-id          4
                                         ::pcp/requires         {:dep {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:dep}
                                         ::pcp/run-next         3
                                         ::pcp/after-nodes      #{5}}
                                      5 {::pcp/node-id  5
                                         ::pcp/requires {:dep {} :direct {} :indirect {}}
                                         ::pcp/run-and  #{4 2}
                                         ::pcp/run-next 1}}
             ::pcp/index-syms        {multi #{1} direct #{2} indirect #{3} dep #{4}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:direct 2 :dep 4 :indirect 3 :multi 1}
             ::pcp/root              5})))

  (testing "and collapsing"
    (is (= (compute-run-graph
             '{::pci/index-oir {:a {#{:c :b :d} #{a}}
                                :b {#{} #{b}}
                                :c {#{:e} #{c}}
                                :d {#{} #{d}}
                                :e {#{} #{e}}}
               ::eql/query     [:a]})
           '{::pcp/nodes             {1 {::pco/name             a
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {:c {} :b {} :d {}}
                                         ::pcp/after-nodes      #{5}
                                         ::pcp/source-for-attrs #{:a}}
                                      2 {::pco/name             c
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:c {}}
                                         ::pcp/input            {:e {}}
                                         ::pcp/after-nodes      #{3}
                                         ::pcp/source-for-attrs #{:c}}
                                      3 {::pco/name             e
                                         ::pcp/node-id          3
                                         ::pcp/requires         {:e {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:e}
                                         ::pcp/run-next         2
                                         ::pcp/after-nodes      #{5}}
                                      4 {::pco/name             b
                                         ::pcp/node-id          4
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:b}
                                         ::pcp/after-nodes      #{5}}
                                      5 {::pcp/node-id  5
                                         ::pcp/requires {:b {} :c {} :e {} :d {}}
                                         ::pcp/run-and  #{4 3 6}
                                         ::pcp/run-next 1}
                                      6 {::pco/name             d
                                         ::pcp/node-id          6
                                         ::pcp/requires         {:d {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:d}
                                         ::pcp/after-nodes      #{5}}}
             ::pcp/index-syms        {a #{1} c #{2} e #{3} b #{4} d #{6}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:e 3 :c 2 :b 4 :d 6 :a 1}
             ::pcp/root              5})))

  (testing "adding multiple ands"
    (is (= (compute-run-graph
             '{::pci/index-oir {:a {#{:c :b} #{a}}
                                :b {#{} #{b}}
                                :c {#{} #{c}}
                                :d {#{:c :b} #{d}}}
               ::eql/query     [:a :d]})
           '{::pcp/nodes             {1 {::pco/name             a
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {:c {} :b {}}
                                         ::pcp/after-nodes      #{7}
                                         ::pcp/source-for-attrs #{:a}}
                                      2 {::pco/name             c
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:c {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:c}
                                         ::pcp/after-nodes      #{6}}
                                      3 {::pco/name             b
                                         ::pcp/node-id          3
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:b}
                                         ::pcp/after-nodes      #{6}}
                                      5 {::pco/name        d
                                         ::pcp/node-id     5
                                         ::pcp/requires    {:d {}}
                                         ::pcp/input       {:c {} :b {}}
                                         ::pcp/after-nodes #{7}}
                                      6 {::pcp/node-id  6
                                         ::pcp/requires {:b {} :c {}}
                                         ::pcp/run-and  #{3 2}
                                         ::pcp/run-next 7}
                                      7 {::pcp/node-id  7
                                         ::pcp/requires {:a {} :d {}}
                                         ::pcp/run-and  #{1 5}}}
             ::pcp/index-syms        {a #{1} c #{2} b #{3} d #{5}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:c 2 :b 3 :a 1}
             ::pcp/root              6}))

    (testing "chain with recursive deps"
      (is (= (compute-run-graph
               '{::pci/index-oir {:a {#{:b :c} #{a}}
                                  :b {#{:c} #{b}}
                                  :c {#{} #{c}}}
                 ::eql/query     [:a]})
             '{::pcp/nodes             {1 {::pco/name             a
                                           ::pcp/node-id          1
                                           ::pcp/requires         {:a {}}
                                           ::pcp/input            {:c {} :b {}}
                                           ::pcp/after-nodes      #{3}
                                           ::pcp/source-for-attrs #{:a}}
                                        2 {::pco/name             c
                                           ::pcp/node-id          2
                                           ::pcp/requires         {:c {}}
                                           ::pcp/input            {}
                                           ::pcp/source-for-attrs #{:c}
                                           ::pcp/run-next         3}
                                        3 {::pco/name             b
                                           ::pcp/node-id          3
                                           ::pcp/requires         {:b {}}
                                           ::pcp/input            {:c {}}
                                           ::pcp/after-nodes      #{2}
                                           ::pcp/source-for-attrs #{:b}
                                           ::pcp/run-next         1}}
               ::pcp/index-syms        {a #{1} c #{2} b #{3}}
               ::pcp/unreachable-syms  #{}
               ::pcp/unreachable-attrs #{}
               ::pcp/index-attrs       {:c 2 :b 3 :a 1}
               ::pcp/root              2}))

      (is (= (compute-run-graph
               '{::pci/index-oir {:a {#{:b :c :d} #{a}}
                                  :b {#{:c :d} #{b}}
                                  :c {#{:d} #{c}}
                                  :d {#{} #{d}}}
                 ::eql/query     [:a]})
             '{::pcp/nodes             {1 {::pco/name             a
                                           ::pcp/node-id          1
                                           ::pcp/requires         {:a {}}
                                           ::pcp/input            {:c {} :b {} :d {}}
                                           ::pcp/after-nodes      #{4}
                                           ::pcp/source-for-attrs #{:a}}
                                        2 {::pco/name             c
                                           ::pcp/node-id          2
                                           ::pcp/requires         {:c {}}
                                           ::pcp/input            {:d {}}
                                           ::pcp/after-nodes      #{3}
                                           ::pcp/source-for-attrs #{:c}
                                           ::pcp/run-next         4}
                                        3 {::pco/name             d
                                           ::pcp/node-id          3
                                           ::pcp/requires         {:d {}}
                                           ::pcp/input            {}
                                           ::pcp/source-for-attrs #{:d}
                                           ::pcp/run-next         2}
                                        4 {::pco/name             b
                                           ::pcp/node-id          4
                                           ::pcp/requires         {:b {}}
                                           ::pcp/input            {:c {} :d {}}
                                           ::pcp/after-nodes      #{2}
                                           ::pcp/source-for-attrs #{:b}
                                           ::pcp/run-next         1}}
               ::pcp/index-syms        {a #{1} c #{2} d #{3} b #{4}}
               ::pcp/unreachable-syms  #{}
               ::pcp/unreachable-attrs #{}
               ::pcp/index-attrs       {:d 3 :c 2 :b 4 :a 1}
               ::pcp/root              3})))

    (is (= (compute-run-graph
             '{::pci/index-oir {:a {#{:c :b} #{a}
                                    #{:e}    #{a1}}
                                :b {#{} #{b}}
                                :c {#{:d} #{c c2 c3}}
                                :d {#{:c :b} #{d}
                                    #{:a}    #{d2}}
                                :e {#{:b :f} #{e}}
                                :f {#{} #{f}}}
               ::eql/query     [:a :e]})
           '{::pcp/nodes             {10 {::pco/name             a1
                                          ::pcp/node-id          10
                                          ::pcp/requires         {:a {}}
                                          ::pcp/input            {:e {}}
                                          ::pcp/after-nodes      #{11}
                                          ::pcp/source-for-attrs #{:a}}
                                      11 {::pco/name             e
                                          ::pcp/node-id          11
                                          ::pcp/requires         {:e {}}
                                          ::pcp/input            {:b {} :f {}}
                                          ::pcp/after-nodes      #{14}
                                          ::pcp/source-for-attrs #{:e}
                                          ::pcp/run-next         10}
                                      12 {::pco/name             b
                                          ::pcp/node-id          12
                                          ::pcp/requires         {:b {}}
                                          ::pcp/input            {}
                                          ::pcp/source-for-attrs #{:b}
                                          ::pcp/after-nodes      #{14}}
                                      13 {::pco/name             f
                                          ::pcp/node-id          13
                                          ::pcp/requires         {:f {}}
                                          ::pcp/input            {}
                                          ::pcp/source-for-attrs #{:f}
                                          ::pcp/after-nodes      #{14}}
                                      14 {::pcp/node-id  14
                                          ::pcp/requires {:f {} :e {} :b {}}
                                          ::pcp/run-and  #{13 12}
                                          ::pcp/run-next 11}}
             ::pcp/index-syms        {a1 #{10} e #{11} b #{12} f #{13}}
             ::pcp/unreachable-syms  #{a d2 c3 c2 c d}
             ::pcp/unreachable-attrs #{:c :d}
             ::pcp/index-attrs       {:b 12 :f 13 :e 11 :a 10}
             ::pcp/root              14}))

    (is (= (compute-run-graph
             '{::pci/index-oir {:a {#{:c :b} #{a}}
                                :b {#{} #{b}}
                                :c {#{} #{c}}
                                :d {#{:c :b} #{d}}
                                :e {#{:c :b :f} #{e}}
                                :f {#{} #{f}}}
               ::eql/query     [:a :d :e]})
           '{::pcp/nodes             {7  {::pcp/node-id  7
                                          ::pcp/requires {:a {} :d {}}
                                          ::pcp/run-and  #{1 5}}
                                      1  {::pco/name             a
                                          ::pcp/node-id          1
                                          ::pcp/requires         {:a {}}
                                          ::pcp/input            {:c {} :b {}}
                                          ::pcp/after-nodes      #{7}
                                          ::pcp/source-for-attrs #{:a}}
                                      6  {::pcp/node-id     6
                                          ::pcp/requires    {:b {} :c {}}
                                          ::pcp/run-and     #{3 2}
                                          ::pcp/run-next    7
                                          ::pcp/after-nodes #{11}}
                                      3  {::pco/name             b
                                          ::pcp/node-id          3
                                          ::pcp/requires         {:b {}}
                                          ::pcp/input            {}
                                          ::pcp/source-for-attrs #{:b}
                                          ::pcp/after-nodes      #{6 9}}
                                      2  {::pco/name             c
                                          ::pcp/node-id          2
                                          ::pcp/requires         {:c {}}
                                          ::pcp/input            {}
                                          ::pcp/source-for-attrs #{:c}
                                          ::pcp/after-nodes      #{6 9}}
                                      11 {::pcp/node-id  11
                                          ::pcp/requires {:b {} :c {} :f {}}
                                          ::pcp/run-and  #{6 9}}
                                      9  {::pcp/node-id     9
                                          ::pcp/requires    {:b {} :c {} :f {}}
                                          ::pcp/run-and     #{3 2 10}
                                          ::pcp/run-next    8
                                          ::pcp/after-nodes #{11}}
                                      5  {::pco/name        d
                                          ::pcp/node-id     5
                                          ::pcp/requires    {:d {}}
                                          ::pcp/input       {:c {} :b {}}
                                          ::pcp/after-nodes #{7}}
                                      10 {::pco/name             f
                                          ::pcp/node-id          10
                                          ::pcp/requires         {:f {}}
                                          ::pcp/input            {}
                                          ::pcp/source-for-attrs #{:f}
                                          ::pcp/after-nodes      #{9}}
                                      8  {::pco/name             e
                                          ::pcp/node-id          8
                                          ::pcp/requires         {:e {}}
                                          ::pcp/input            {:c {} :b {} :f {}}
                                          ::pcp/after-nodes      #{9}
                                          ::pcp/source-for-attrs #{:e}}}
             ::pcp/index-syms        {a #{1} c #{2} b #{3} d #{5} e #{8} f #{10}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:c 2 :b 3 :a 1 :f 10 :e 8}
             ::pcp/root              11}))

    (is (= (compute-run-graph
             '{::pci/index-oir {:a {#{:c :b} #{a}}
                                :b {#{} #{b}}
                                :c {#{} #{c}}
                                :d {#{:e :b} #{d}}
                                :e {#{} #{e}}
                                :f {#{} #{f}}}
               ::eql/query     [:a :d]})
           '{::pcp/nodes             {1 {::pco/name             a
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {:c {} :b {}}
                                         ::pcp/after-nodes      #{4}
                                         ::pcp/source-for-attrs #{:a}}
                                      2 {::pco/name             c
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:c {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:c}
                                         ::pcp/after-nodes      #{4}}
                                      3 {::pco/name             b
                                         ::pcp/node-id          3
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:b}
                                         ::pcp/after-nodes      #{7 4}}
                                      4 {::pcp/node-id     4
                                         ::pcp/requires    {:b {} :c {}}
                                         ::pcp/run-and     #{3 2}
                                         ::pcp/run-next    1
                                         ::pcp/after-nodes #{8}}
                                      5 {::pco/name             d
                                         ::pcp/node-id          5
                                         ::pcp/requires         {:d {}}
                                         ::pcp/input            {:e {} :b {}}
                                         ::pcp/after-nodes      #{7}
                                         ::pcp/source-for-attrs #{:d}}
                                      6 {::pco/name             e
                                         ::pcp/node-id          6
                                         ::pcp/requires         {:e {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:e}
                                         ::pcp/after-nodes      #{7}}
                                      7 {::pcp/node-id     7
                                         ::pcp/requires    {:b {} :e {}}
                                         ::pcp/run-and     #{3 6}
                                         ::pcp/run-next    5
                                         ::pcp/after-nodes #{8}}
                                      8 {::pcp/node-id  8
                                         ::pcp/requires {:b {} :e {} :c {}}
                                         ::pcp/run-and  #{7 4}}}
             ::pcp/index-syms        {a #{1} c #{2} b #{3} d #{5} e #{6}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:c 2 :b 3 :a 1 :e 6 :d 5}
             ::pcp/root              8}))

    #_(is (= (compute-run-graph
               '{::pci/index-oir {:a {#{:b} #{a}
                                      #{:e} #{a1}}
                                  :b {#{} #{b}}
                                  :c {#{} #{c}}
                                  :d {#{:b}    #{d}
                                      #{:a :e} #{d1}}
                                  :e {#{:b :c} #{e}}
                                  :f {#{} #{f}}}
                 ::eql/query     [:d]})
             {})))

  (testing "multiple attribute request on a single resolver"
    (testing "missing provides"
      (is (= (compute-run-graph
               {::pci/index-oir {:a {#{} #{'a}}
                                 :b {#{} #{'a}}}
                ::eql/query     [:a :b]})
             '{::pcp/nodes             {1 {::pco/name             a
                                           ::pcp/node-id          1
                                           ::pcp/requires         {:a {} :b {}}
                                           ::pcp/input            {}
                                           ::pcp/source-for-attrs #{:b :a}}}
               ::pcp/index-syms        {a #{1}}
               ::pcp/unreachable-syms  #{}
               ::pcp/unreachable-attrs #{}
               ::pcp/index-attrs       {:a 1 :b 1}
               ::pcp/root              1}))))

  (testing "add requires to appropriated node"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'z
                            ::pco/output [:z]}
                           {::pco/name   'a
                            ::pco/input  [:z]
                            ::pco/output [:a :b]}]
              ::eql/query [:a :b]})
           '{::pcp/nodes             {2 {::pco/name             z
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:z {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:z}
                                         ::pcp/run-next         3}
                                      3 {::pco/name             a
                                         ::pcp/node-id          3
                                         ::pcp/requires         {:b {} :a {}}
                                         ::pcp/input            {:z {}}
                                         ::pcp/source-for-attrs #{:b :a}
                                         ::pcp/after-nodes      #{2}}}
             ::pcp/index-syms        {a #{3} z #{2}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:z 2 :a 3 :b 3}
             ::pcp/root              2}))

    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'z
                            ::pco/output [:z]}
                           {::pco/name   'a
                            ::pco/input  [:z]
                            ::pco/output [:a :b]}
                           {::pco/name   'c
                            ::pco/input  [:b]
                            ::pco/output [:c]}]
              ::eql/query [:c :a]})
           '{::pcp/nodes             {1 {::pco/name             c
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:c {}}
                                         ::pcp/input            {:b {}}
                                         ::pcp/after-nodes      #{4}
                                         ::pcp/source-for-attrs #{:c}}
                                      3 {::pco/name             z
                                         ::pcp/node-id          3
                                         ::pcp/requires         {:z {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:z}
                                         ::pcp/run-next         4}
                                      4 {::pco/name             a
                                         ::pcp/node-id          4
                                         ::pcp/requires         {:a {} :b {}}
                                         ::pcp/input            {:z {}}
                                         ::pcp/run-next         1
                                         ::pcp/source-for-attrs #{:b :a}
                                         ::pcp/after-nodes      #{3}}}
             ::pcp/index-syms        {c #{1} a #{4} z #{3}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:z 3 :b 4 :c 1 :a 4}
             ::pcp/root              3})))

  (testing "single dependency"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}
                           {::pco/name   'b
                            ::pco/input  [:a]
                            ::pco/output [:b]}]
              ::eql/query [:b]})
           '{::pcp/nodes             {1 {::pco/name             b
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {:a {}}
                                         ::pcp/after-nodes      #{2}
                                         ::pcp/source-for-attrs #{:b}}
                                      2 {::pco/name             a
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {}
                                         ::pcp/run-next         1
                                         ::pcp/source-for-attrs #{:a}}}
             ::pcp/index-syms        {b #{1} a #{2}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/root              2
             ::pcp/index-attrs       {:a 2 :b 1}})))

  (testing "optimize multiple resolver calls"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}
                           {::pco/name   'a2
                            ::pco/input  [:b]
                            ::pco/output [:a]}
                           {::pco/name   'b
                            ::pco/output [:b]}]
              ::eql/query [:a]})
           '{::pcp/nodes             {1 {::pco/name        a
                                         ::pcp/node-id     1
                                         ::pcp/requires    {:a {}}
                                         ::pcp/input       {}
                                         ::pcp/after-nodes #{4}}
                                      2 {::pco/name        a2
                                         ::pcp/node-id     2
                                         ::pcp/requires    {:a {}}
                                         ::pcp/input       {:b {}}
                                         ::pcp/after-nodes #{3}}
                                      3 {::pco/name             b
                                         ::pcp/node-id          3
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {}
                                         ::pcp/run-next         2
                                         ::pcp/source-for-attrs #{:b}
                                         ::pcp/after-nodes      #{4}}
                                      4 {::pcp/node-id          4
                                         ::pcp/requires         {:a {}}
                                         ::pcp/run-or           #{3 1}
                                         ::pcp/source-for-attrs #{:a}}}
             ::pcp/index-syms        {a #{1} a2 #{2} b #{3}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/root              4
             ::pcp/index-attrs       {:b 3 :a 4}}))

    (testing "create and root path for quicker dependency dispatch"
      (is (= (compute-run-graph
               {::resolvers [{::pco/name   'a
                              ::pco/output [:a]}
                             {::pco/name   'a2
                              ::pco/input  [:b]
                              ::pco/output [:a]}
                             {::pco/name   'b
                              ::pco/output [:b]}]
                ::eql/query [:a :b]})
             '{::pcp/nodes             {1 {::pco/name        a
                                           ::pcp/node-id     1
                                           ::pcp/requires    {:a {}}
                                           ::pcp/input       {}
                                           ::pcp/after-nodes #{4}}
                                        2 {::pco/name        a2
                                           ::pcp/node-id     2
                                           ::pcp/requires    {:a {}}
                                           ::pcp/input       {:b {}}
                                           ::pcp/after-nodes #{3}}
                                        3 {::pco/name             b
                                           ::pcp/node-id          3
                                           ::pcp/requires         {:b {}}
                                           ::pcp/input            {}
                                           ::pcp/source-for-attrs #{:b}
                                           ::pcp/run-next         2
                                           ::pcp/after-nodes      #{4 5}}
                                        4 {::pcp/node-id          4
                                           ::pcp/requires         {:a {}}
                                           ::pcp/run-or           #{1 3}
                                           ::pcp/source-for-attrs #{:a}
                                           ::pcp/after-nodes      #{5}}
                                        5 {::pcp/node-id  5
                                           ::pcp/requires {:b {} :a {}}
                                           ::pcp/run-and  #{4 3}}}
               ::pcp/index-syms        {a #{1} a2 #{2} b #{3}}
               ::pcp/unreachable-syms  #{}
               ::pcp/unreachable-attrs #{}
               ::pcp/index-attrs       {:b 3 :a 4}
               ::pcp/root              5}))))

  (testing "single dependency with extra provides"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}
                           {::pco/name   'b
                            ::pco/input  [:a]
                            ::pco/output [:b :b2 :b3]}]
              ::eql/query [:b]})
           '{::pcp/nodes             {1 {::pco/name             b
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {:a {}}
                                         ::pcp/after-nodes      #{2}
                                         ::pcp/source-for-attrs #{:b}}
                                      2 {::pco/name             a
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {}
                                         ::pcp/run-next         1
                                         ::pcp/source-for-attrs #{:a}}}
             ::pcp/index-syms        {b #{1} a #{2}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/root              2
             ::pcp/index-attrs       {:a 2 :b 1}})))

  (testing "dependency chain"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}
                           {::pco/name   'b
                            ::pco/input  [:a]
                            ::pco/output [:b]}
                           {::pco/name   'c
                            ::pco/input  [:b]
                            ::pco/output [:c]}]
              ::eql/query [:c]})
           '{::pcp/nodes             {1 {::pco/name             c
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:c {}}
                                         ::pcp/input            {:b {}}
                                         ::pcp/after-nodes      #{2}
                                         ::pcp/source-for-attrs #{:c}}
                                      2 {::pco/name             b
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {:a {}}
                                         ::pcp/run-next         1
                                         ::pcp/after-nodes      #{3}
                                         ::pcp/source-for-attrs #{:b}}
                                      3 {::pco/name             a
                                         ::pcp/node-id          3
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {}
                                         ::pcp/run-next         2
                                         ::pcp/source-for-attrs #{:a}}}
             ::pcp/index-syms        {c #{1} b #{2} a #{3}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/root              3
             ::pcp/index-attrs       {:a 3 :b 2 :c 1}})))

  (testing "dependency chain with available data"
    (is (= (compute-run-graph
             {::resolvers          [{::pco/name   'b
                                     ::pco/input  [:a]
                                     ::pco/output [:b]}
                                    {::pco/name   'c
                                     ::pco/input  [:b]
                                     ::pco/output [:c]}]
              ::eql/query          [:c]
              ::pcp/available-data {:a {}}})
           '{::pcp/nodes             {1 {::pco/name             c
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:c {}}
                                         ::pcp/input            {:b {}}
                                         ::pcp/after-nodes      #{2}
                                         ::pcp/source-for-attrs #{:c}}
                                      2 {::pco/name             b
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {:a {}}
                                         ::pcp/run-next         1
                                         ::pcp/source-for-attrs #{:b}}}
             ::pcp/index-syms        {c #{1} b #{2}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/root              2
             ::pcp/index-attrs       {:b 2 :c 1}})))

  (testing "multiple paths chain at root"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}
                           {::pco/name   'a2
                            ::pco/output [:a]}
                           {::pco/name   'b
                            ::pco/input  [:a]
                            ::pco/output [:b]}]
              ::eql/query [:b]})
           '{::pcp/nodes             {1 {::pco/name             b
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {:a {}}
                                         ::pcp/after-nodes      #{4}
                                         ::pcp/source-for-attrs #{:b}}
                                      2 {::pco/name        a
                                         ::pcp/node-id     2
                                         ::pcp/requires    {:a {}}
                                         ::pcp/input       {}
                                         ::pcp/after-nodes #{4}}
                                      3 {::pco/name        a2
                                         ::pcp/node-id     3
                                         ::pcp/requires    {:a {}}
                                         ::pcp/input       {}
                                         ::pcp/after-nodes #{4}}
                                      4 {::pcp/node-id          4
                                         ::pcp/requires         {:a {}}
                                         ::pcp/run-or           #{2 3}
                                         ::pcp/run-next         1
                                         ::pcp/source-for-attrs #{:a}}}
             ::pcp/index-syms        {b #{1} a #{2} a2 #{3}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/root              4
             ::pcp/index-attrs       {:a 4 :b 1}})))

  (testing "multiple paths chain at edge"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}
                           {::pco/name   'b
                            ::pco/input  [:a]
                            ::pco/output [:b]}
                           {::pco/name   'b2
                            ::pco/input  [:a]
                            ::pco/output [:b]}]
              ::eql/query [:b]})
           #?(:clj
              '{::pcp/nodes             {1 {::pco/name        b2
                                            ::pcp/node-id     1
                                            ::pcp/requires    {:b {}}
                                            ::pcp/input       {:a {}}
                                            ::pcp/after-nodes #{3}}
                                         2 {::pco/name        b
                                            ::pcp/node-id     2
                                            ::pcp/requires    {:b {}}
                                            ::pcp/input       {:a {}}
                                            ::pcp/after-nodes #{3}}
                                         3 {::pcp/node-id          3
                                            ::pcp/requires         {:b {}}
                                            ::pcp/run-or           #{1 2}
                                            ::pcp/after-nodes      #{4}
                                            ::pcp/source-for-attrs #{:b}}
                                         4 {::pco/name             a
                                            ::pcp/node-id          4
                                            ::pcp/requires         {:a {}}
                                            ::pcp/input            {}
                                            ::pcp/run-next         3
                                            ::pcp/source-for-attrs #{:a}}}
                ::pcp/index-syms        {b2 #{1} b #{2} a #{4}}
                ::pcp/unreachable-syms  #{}
                ::pcp/unreachable-attrs #{}
                ::pcp/root              4
                ::pcp/index-attrs       {:a 4 :b 3}}
              :cljs
              '{::pcp/nodes             {1 {::pco/name        b
                                            ::pcp/node-id     1
                                            ::pcp/requires    {:b {}}
                                            ::pcp/input       {:a {}}
                                            ::pcp/after-nodes #{3}}
                                         2 {::pco/name        b2
                                            ::pcp/node-id     2
                                            ::pcp/requires    {:b {}}
                                            ::pcp/input       {:a {}}
                                            ::pcp/after-nodes #{3}}
                                         3 {::pcp/node-id          3
                                            ::pcp/requires         {:b {}}
                                            ::pcp/run-or           #{1 2}
                                            ::pcp/after-nodes      #{4}
                                            ::pcp/source-for-attrs #{:b}}
                                         4 {::pco/name             a
                                            ::pcp/node-id          4
                                            ::pcp/requires         {:a {}}
                                            ::pcp/input            {}
                                            ::pcp/run-next         3
                                            ::pcp/source-for-attrs #{:a}}}
                ::pcp/index-syms        {b2 #{2} b #{1} a #{4}}
                ::pcp/unreachable-syms  #{}
                ::pcp/unreachable-attrs #{}
                ::pcp/root              4
                ::pcp/index-attrs       {:a 4 :b 3}}))))

  (testing "multiple inputs"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}
                           {::pco/name   'b
                            ::pco/output [:b]}
                           {::pco/name   'c
                            ::pco/input  [:a :b]
                            ::pco/output [:c]}]
              ::eql/query [:c]})
           '{::pcp/nodes             {1 {::pco/name             c
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:c {}}
                                         ::pcp/input            {:b {} :a {}}
                                         ::pcp/after-nodes      #{4}
                                         ::pcp/source-for-attrs #{:c}}
                                      2 {::pco/name             b
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:b}
                                         ::pcp/after-nodes      #{4}}
                                      3 {::pco/name             a
                                         ::pcp/node-id          3
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:a}
                                         ::pcp/after-nodes      #{4}}
                                      4 {::pcp/node-id  4
                                         ::pcp/requires {:a {} :b {}}
                                         ::pcp/run-and  #{3 2}
                                         ::pcp/run-next 1}}
             ::pcp/index-syms        {c #{1} b #{2} a #{3}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:b 2 :a 3 :c 1}
             ::pcp/root              4})))

  (testing "skip resolves that have self dependency"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}
                           {::pco/name   'c
                            ::pco/input  [:a :c]
                            ::pco/output [:c]}
                           {::pco/name   'c2
                            ::pco/input  [:a]
                            ::pco/output [:c]}]
              ::eql/query [:c]})
           '{::pcp/nodes             {1 {::pco/name             c2
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:c {}}
                                         ::pcp/input            {:a {}}
                                         ::pcp/after-nodes      #{2}
                                         ::pcp/source-for-attrs #{:c}}
                                      2 {::pco/name             a
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {}
                                         ::pcp/run-next         1
                                         ::pcp/source-for-attrs #{:a}}}
             ::pcp/index-syms        {c2 #{1} a #{2}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/root              2
             ::pcp/index-attrs       {:a 2 :c 1}})))

  (testing "multiple inputs with different tail sizes"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}
                           {::pco/name   'a1
                            ::pco/output [:a]}
                           {::pco/name   'b
                            ::pco/output [:b]}
                           {::pco/name   'c
                            ::pco/input  [:a :b]
                            ::pco/output [:c]}]
              ::eql/query [:c]})
           '{::pcp/nodes             {1 {::pco/name             c
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:c {}}
                                         ::pcp/input            {:b {} :a {}}
                                         ::pcp/after-nodes      #{6}
                                         ::pcp/source-for-attrs #{:c}}
                                      2 {::pco/name             b
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:b}
                                         ::pcp/after-nodes      #{6}}
                                      3 {::pco/name        a
                                         ::pcp/node-id     3
                                         ::pcp/requires    {:a {}}
                                         ::pcp/input       {}
                                         ::pcp/after-nodes #{5}}
                                      4 {::pco/name        a1
                                         ::pcp/node-id     4
                                         ::pcp/requires    {:a {}}
                                         ::pcp/input       {}
                                         ::pcp/after-nodes #{5}}
                                      5 {::pcp/node-id          5
                                         ::pcp/requires         {:a {}}
                                         ::pcp/run-or           #{3 4}
                                         ::pcp/source-for-attrs #{:a}
                                         ::pcp/after-nodes      #{6}}
                                      6 {::pcp/node-id  6
                                         ::pcp/requires {:a {} :b {}}
                                         ::pcp/run-and  #{5 2}
                                         ::pcp/run-next 1}}
             ::pcp/index-syms        {c #{1} b #{2} a #{3} a1 #{4}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:b 2 :a 5 :c 1}
             ::pcp/root              6})))

  (testing "multiple calls to same resolver"
    (is (= (compute-run-graph
             {::resolvers '[{::pco/name   a
                             ::pco/input  [:c]
                             ::pco/output [:a]}
                            {::pco/name   b
                             ::pco/input  [:d]
                             ::pco/output [:b]}
                            {::pco/name   cd
                             ::pco/output [:c :d]}
                            {::pco/name   d
                             ::pco/output [:d]}]
              ::eql/query [:a :b]})
           '{::pcp/nodes             {1 {::pco/name             a
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {:c {}}
                                         ::pcp/after-nodes      #{2}
                                         ::pcp/source-for-attrs #{:a}}
                                      2 {::pco/name             cd
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:c {}}
                                         ::pcp/input            {}
                                         ::pcp/run-next         1
                                         ::pcp/source-for-attrs #{:c}
                                         ::pcp/after-nodes      #{7}}
                                      3 {::pco/name             b
                                         ::pcp/node-id          3
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {:d {}}
                                         ::pcp/after-nodes      #{6}
                                         ::pcp/source-for-attrs #{:b}}
                                      4 {::pco/name        cd
                                         ::pcp/node-id     4
                                         ::pcp/requires    {:d {}}
                                         ::pcp/input       {}
                                         ::pcp/after-nodes #{6}}
                                      5 {::pco/name        d
                                         ::pcp/node-id     5
                                         ::pcp/requires    {:d {}}
                                         ::pcp/input       {}
                                         ::pcp/after-nodes #{6}}
                                      6 {::pcp/node-id          6
                                         ::pcp/requires         {:d {}}
                                         ::pcp/run-or           #{4 5}
                                         ::pcp/run-next         3
                                         ::pcp/source-for-attrs #{:d}
                                         ::pcp/after-nodes      #{7}}
                                      7 {::pcp/node-id  7
                                         ::pcp/requires {:d {} :c {}}
                                         ::pcp/run-and  #{6 2}}}
             ::pcp/index-syms        {a #{1} cd #{4 2} b #{3} d #{5}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:c 2 :a 1 :d 6 :b 3}
             ::pcp/root              7})))

  (testing "diamond shape deps"
    (is (= (compute-run-graph
             '{::pci/index-oir {:a {#{} #{a}}
                                :b {#{:a} #{b}}
                                :c {#{:a} #{c}}
                                :d {#{:c :b} #{d}}}
               ::eql/query     [:d]})
           '{::pcp/nodes             {1 {::pco/name             d
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:d {}}
                                         ::pcp/input            {:c {} :b {}}
                                         ::pcp/after-nodes      #{5}
                                         ::pcp/source-for-attrs #{:d}}
                                      2 {::pco/name             c
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:c {}}
                                         ::pcp/input            {:a {}}
                                         ::pcp/after-nodes      #{5}
                                         ::pcp/source-for-attrs #{:c}}
                                      3 {::pco/name             a
                                         ::pcp/node-id          3
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:a}
                                         ::pcp/run-next         5}
                                      4 {::pco/name             b
                                         ::pcp/node-id          4
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {:a {}}
                                         ::pcp/after-nodes      #{5}
                                         ::pcp/source-for-attrs #{:b}}
                                      5 {::pcp/node-id     5
                                         ::pcp/requires    {:c {} :b {}}
                                         ::pcp/run-and     #{2 4}
                                         ::pcp/after-nodes #{3}
                                         ::pcp/run-next    1}}
             ::pcp/index-syms        {d #{1} c #{2} a #{3} b #{4}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:a 3 :c 2 :b 4 :d 1}
             ::pcp/root              3})))

  (testing "diamond shape deps with tail"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'z
                            ::pco/output [:z]}
                           {::pco/name   'a
                            ::pco/input  [:z]
                            ::pco/output [:a]}
                           {::pco/name   'b
                            ::pco/input  [:a]
                            ::pco/output [:b]}
                           {::pco/name   'c
                            ::pco/input  [:a]
                            ::pco/output [:c]}
                           {::pco/name   'd
                            ::pco/input  [:b :c]
                            ::pco/output [:d]}]
              ::eql/query [:d]})
           '{::pcp/nodes             {1 {::pco/name             d
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:d {}}
                                         ::pcp/input            {:c {} :b {}}
                                         ::pcp/after-nodes      #{6}
                                         ::pcp/source-for-attrs #{:d}}
                                      2 {::pco/name             c
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:c {}}
                                         ::pcp/input            {:a {}}
                                         ::pcp/after-nodes      #{6}
                                         ::pcp/source-for-attrs #{:c}}
                                      3 {::pco/name             a
                                         ::pcp/node-id          3
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {:z {}}
                                         ::pcp/after-nodes      #{4}
                                         ::pcp/source-for-attrs #{:a}
                                         ::pcp/run-next         6}
                                      4 {::pco/name             z
                                         ::pcp/node-id          4
                                         ::pcp/requires         {:z {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:z}
                                         ::pcp/run-next         3}
                                      5 {::pco/name             b
                                         ::pcp/node-id          5
                                         ::pcp/requires         {:b {}}
                                         ::pcp/input            {:a {}}
                                         ::pcp/after-nodes      #{6}
                                         ::pcp/source-for-attrs #{:b}}
                                      6 {::pcp/node-id     6
                                         ::pcp/requires    {:c {} :b {}}
                                         ::pcp/run-and     #{2 5}
                                         ::pcp/after-nodes #{3}
                                         ::pcp/run-next    1}}
             ::pcp/index-syms        {d #{1} c #{2} a #{3} z #{4} b #{5}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:z 4 :a 3 :c 2 :b 5 :d 1}
             ::pcp/root              4})))

  (testing "deep recurring dependency"
    (is (= (compute-run-graph
             (-> {::eql/query [:release/script :recur-dep]
                  ::resolvers [{::pco/name   'id
                                ::pco/output [:db/id]}
                               {::pco/name   'label-type
                                ::pco/input  [:db/id]
                                ::pco/output [:label/type]}
                               {::pco/name   'release-script
                                ::pco/input  [:db/id]
                                ::pco/output [:release/script]}
                               {::pco/name   'recur-dep
                                ::pco/input  [:label/type]
                                ::pco/output [:recur-dep]}]}))
           '{::pcp/nodes             {1 {::pco/name             release-script
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:release/script {}}
                                         ::pcp/input            {:db/id {}}
                                         ::pcp/after-nodes      #{5}
                                         ::pcp/source-for-attrs #{:release/script}}
                                      2 {::pco/name             id
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:db/id {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:db/id}
                                         ::pcp/run-next         5}
                                      3 {::pco/name             recur-dep
                                         ::pcp/node-id          3
                                         ::pcp/requires         {:recur-dep {}}
                                         ::pcp/input            {:label/type {}}
                                         ::pcp/after-nodes      #{4}
                                         ::pcp/source-for-attrs #{:recur-dep}}
                                      4 {::pco/name             label-type
                                         ::pcp/node-id          4
                                         ::pcp/requires         {:label/type {}}
                                         ::pcp/input            {:db/id {}}
                                         ::pcp/after-nodes      #{5}
                                         ::pcp/source-for-attrs #{:label/type}
                                         ::pcp/run-next         3}
                                      5 {::pcp/node-id     5
                                         ::pcp/requires    {:release/script {} :label/type {}}
                                         ::pcp/run-and     #{1 4}
                                         ::pcp/after-nodes #{2}}}
             ::pcp/index-syms        {release-script #{1}
                                      id             #{2}
                                      recur-dep      #{3}
                                      label-type     #{4}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:db/id          2
                                      :release/script 1
                                      :label/type     4
                                      :recur-dep      3}
             ::pcp/root              2})))

  (testing "push interdependent paths back"
    (is (= (compute-run-graph
             (-> {::eql/query          [:name]
                  ::pcp/available-data {:id {}}
                  ::resolvers          [{::pco/name   'from-other-id
                                         ::pco/input  [:other-id]
                                         ::pco/output [:id :name :other-id]}
                                        {::pco/name   'from-id
                                         ::pco/input  [:id]
                                         ::pco/output [:id :name :other-id]}]}))
           '{::pcp/nodes             {1 {::pco/name        from-other-id
                                         ::pcp/node-id     1
                                         ::pcp/requires    {:name {}}
                                         ::pcp/input       {:other-id {}}
                                         ::pcp/after-nodes #{2}}
                                      2 {::pco/name             from-id
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:other-id {} :name {}}
                                         ::pcp/input            {:id {}}
                                         ::pcp/run-next         1
                                         ::pcp/source-for-attrs #{:name :other-id}}}
             ::pcp/index-syms        {from-other-id #{1} from-id #{2}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:other-id 2 :name 2}
             ::pcp/root              2}))

    (is (= (compute-run-graph
             (-> {::eql/query          [:name]
                  ::pcp/available-data {:id {}}
                  ::resolvers          [{::pco/name   'from-id
                                         ::pco/input  [:id]
                                         ::pco/output [:id :name :other-id]}
                                        {::pco/name   'from-other-id
                                         ::pco/input  [:other-id]
                                         ::pco/output [:other-id2]}
                                        {::pco/name   'from-other-id2
                                         ::pco/input  [:other-id2]
                                         ::pco/output [:id :name :other]}]}))
           '{::pcp/nodes             {1 {::pco/name             from-id
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:name {} :other-id {}}
                                         ::pcp/input            {:id {}}
                                         ::pcp/run-next         3
                                         ::pcp/source-for-attrs #{:name :other-id}}
                                      2 {::pco/name        from-other-id2
                                         ::pcp/node-id     2
                                         ::pcp/requires    {:name {}}
                                         ::pcp/input       {:other-id2 {}}
                                         ::pcp/after-nodes #{3}}
                                      3 {::pco/name             from-other-id
                                         ::pcp/node-id          3
                                         ::pcp/requires         {:other-id2 {}}
                                         ::pcp/input            {:other-id {}}
                                         ::pcp/run-next         2
                                         ::pcp/after-nodes      #{1}
                                         ::pcp/source-for-attrs #{:other-id2}}}
             ::pcp/index-syms        {from-id        #{1}
                                      from-other-id2 #{2}
                                      from-other-id  #{3}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/root              1
             ::pcp/index-attrs       {:other-id 1 :other-id2 3 :name 1}}))

    (testing "unless they add something new"
      (is (= (compute-run-graph
               (-> {::eql/query          [:name :other]
                    ::pcp/available-data {:id {}}
                    ::resolvers          [{::pco/name   'from-id
                                           ::pco/input  [:id]
                                           ::pco/output [:id :name :other-id]}
                                          {::pco/name   'from-other-id
                                           ::pco/input  [:other-id]
                                           ::pco/output [:other-id2]}
                                          {::pco/name   'from-other-id2
                                           ::pco/input  [:other-id2]
                                           ::pco/output [:id :name :other]}]}))
             '{::pcp/nodes             {1 {::pco/name             from-id
                                           ::pcp/node-id          1
                                           ::pcp/requires         {:name {} :other-id {}}
                                           ::pcp/input            {:id {}}
                                           ::pcp/run-next         3
                                           ::pcp/source-for-attrs #{:name :other-id}}
                                        3 {::pco/name             from-other-id
                                           ::pcp/node-id          3
                                           ::pcp/requires         {:other-id2 {}}
                                           ::pcp/input            {:other-id {}}
                                           ::pcp/after-nodes      #{1}
                                           ::pcp/source-for-attrs #{:other-id2}
                                           ::pcp/run-next         5}
                                        5 {::pco/name             from-other-id2
                                           ::pcp/node-id          5
                                           ::pcp/requires         {:other {} :name {}}
                                           ::pcp/input            {:other-id2 {}}
                                           ::pcp/after-nodes      #{3}
                                           ::pcp/source-for-attrs #{:other}}}
               ::pcp/index-syms        {from-id        #{1}
                                        from-other-id2 #{5}
                                        from-other-id  #{3}}
               ::pcp/unreachable-syms  #{}
               ::pcp/unreachable-attrs #{}
               ::pcp/index-attrs       {:other-id 1 :other-id2 3 :name 1 :other 5}
               ::pcp/root              1})))))

(deftest compute-run-graph-placeholders-test
  [{:>/p1 [:a]}
   {:>/p2 [:a
           {:b [:x]}]}
   {:>/p3 [:c]}]

  (testing "plan works"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}]
              ::eql/query '[(:a {:x "y"})]})
           '{::pcp/nodes             {1 {::pco/name             a
                                         ::pcp/params           {:x "y"}
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:a}}}
             ::pcp/index-syms        {a #{1}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/root              1
             ::pcp/index-attrs       {:a 1}}))))

(deftest compute-run-graph-params-test
  (testing "add params to resolver call"
    (is (= (compute-run-graph
             {::resolvers [{::pco/name   'a
                            ::pco/output [:a]}]
              ::eql/query '[(:a {:x "y"})]})
           '{::pcp/nodes             {1 {::pco/name             a
                                         ::pcp/params           {:x "y"}
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:a {}}
                                         ::pcp/input            {}
                                         ::pcp/source-for-attrs #{:a}}}
             ::pcp/index-syms        {a #{1}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/root              1
             ::pcp/index-attrs       {:a 1}})))

  (testing "params while collapsing"
    (testing "params come from first node"
      (is (= (compute-run-graph
               {::pci/index-oir '{:a {#{} #{a}}
                                  :b {#{} #{a}}}
                ::eql/query     '[(:a {:x 1}) :b]})
             '{::pcp/nodes             {1 {::pco/name             a
                                           ::pcp/params           {:x 1}
                                           ::pcp/node-id          1
                                           ::pcp/requires         {:a {} :b {}}
                                           ::pcp/input            {}
                                           ::pcp/source-for-attrs #{:a :b}}}
               ::pcp/index-syms        {a #{1}}
               ::pcp/unreachable-syms  #{}
               ::pcp/unreachable-attrs #{}
               ::pcp/index-attrs       {:a 1 :b 1}
               ::pcp/root              1})))

    (testing "getting params from the later node"
      (is (= (compute-run-graph
               {::pci/index-oir '{:a {#{} #{a}}
                                  :b {#{} #{a}}}
                ::eql/query     '[:a (:b {:x 1})]})
             '{::pcp/nodes             {1 {::pco/name             a
                                           ::pcp/params           {:x 1}
                                           ::pcp/node-id          1
                                           ::pcp/requires         {:a {} :b {}}
                                           ::pcp/input            {}
                                           ::pcp/source-for-attrs #{:a :b}}}
               ::pcp/index-syms        {a #{1}}
               ::pcp/unreachable-syms  #{}
               ::pcp/unreachable-attrs #{}
               ::pcp/index-attrs       {:a 1 :b 1}
               ::pcp/root              1})))

    (testing "merging params"
      (is (= (compute-run-graph
               {::pci/index-oir '{:a {#{} #{a}}
                                  :b {#{} #{a}}}
                ::eql/query     '[(:a {:x 1}) (:b {:y 2})]})
             '{::pcp/nodes             {1 {::pco/name             a
                                           ::pcp/params           {:x 1
                                                                   :y 2}
                                           ::pcp/node-id          1
                                           ::pcp/requires         {:a {} :b {}}
                                           ::pcp/input            {}
                                           ::pcp/source-for-attrs #{:a :b}}}
               ::pcp/index-syms        {a #{1}}
               ::pcp/unreachable-syms  #{}
               ::pcp/unreachable-attrs #{}
               ::pcp/index-attrs       {:a 1 :b 1}
               ::pcp/root              1})))

    (testing "conflicting params"
      (is (= (compute-run-graph
               {::pci/index-oir '{:a {#{} #{a}}
                                  :b {#{} #{a}}}
                ::eql/query     '[(:a {:x 1}) (:b {:x 2})]})
             '{::pcp/nodes             {1 {::pco/name             a
                                           ::pcp/params           {:x 2}
                                           ::pcp/node-id          1
                                           ::pcp/requires         {:a {} :b {}}
                                           ::pcp/input            {}
                                           ::pcp/source-for-attrs #{:a :b}}}
               ::pcp/index-syms        {a #{1}}
               ::pcp/unreachable-syms  #{}
               ::pcp/unreachable-attrs #{}
               ::pcp/index-attrs       {:a 1 :b 1}
               ::pcp/root              1
               ::pcp/warnings          [{::pcp/node-id         1
                                         ::pcp/warn            "Conflicting params on resolver call."
                                         ::pcp/conflict-params #{:x}}]})))

    (testing "its not a conflict when the values are the same."
      (is (= (compute-run-graph
               {::pci/index-oir '{:a {#{} #{a}}
                                  :b {#{} #{a}}}
                ::eql/query     '[(:a {:x 1}) (:b {:x 1})]})
             '{::pcp/nodes             {1 {::pco/name             a
                                           ::pcp/params           {:x 1}
                                           ::pcp/node-id          1
                                           ::pcp/requires         {:a {} :b {}}
                                           ::pcp/input            {}
                                           ::pcp/source-for-attrs #{:a :b}}}
               ::pcp/index-syms        {a #{1}}
               ::pcp/unreachable-syms  #{}
               ::pcp/unreachable-attrs #{}
               ::pcp/index-attrs       {:a 1 :b 1}
               ::pcp/root              1})))))

(deftest compute-run-graph-dynamic-resolvers-test
  (testing "unreachable"
    (is (= (compute-run-graph
             {::pci/index-resolvers {'dynamic-resolver {::pco/name              'dynamic-resolver
                                                        ::pco/cache?            false
                                                        ::pco/dynamic-resolver? true
                                                        ::pco/resolve           (fn [_ _])}}
              ::pci/index-oir       {:release/script {#{:db/id} #{'dynamic-resolver}}}
              ::eql/query           [:release/script]})
           {::pcp/nodes             {}
            ::pcp/index-syms        {}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{:db/id}})))

  (testing "simple dynamic call"
    (is (= (compute-run-graph
             {::pci/index-resolvers {'dynamic-resolver
                                     {::pco/name              'dynamic-resolver
                                      ::pco/cache?            false
                                      ::pco/dynamic-resolver? true
                                      ::pco/resolve           (fn [_ _])}}
              ::pci/index-oir       {:release/script {#{:db/id} #{'dynamic-resolver}}}
              ::pcp/available-data  {:db/id {}}
              ::eql/query           [:release/script]})

           {::pcp/nodes             {1 {::pco/name             'dynamic-resolver
                                        ::pcp/node-id          1
                                        ::pcp/requires         {:release/script {}}
                                        ::pcp/input            {:db/id {}}
                                        ::pcp/source-for-attrs #{:release/script}
                                        ::pcp/foreign-ast      (eql/query->ast [:release/script])}}
            ::pcp/index-syms        {'dynamic-resolver #{1}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/root              1
            ::pcp/index-attrs       {:release/script 1}}))

    (testing "retain params"
      (is (= (compute-run-graph
               {::pci/index-resolvers {'dynamic-resolver
                                       {::pco/name              'dynamic-resolver
                                        ::pco/cache?            false
                                        ::pco/dynamic-resolver? true
                                        ::pco/resolve           (fn [_ _])}}
                ::pci/index-oir       {:release/script {#{:db/id} #{'dynamic-resolver}}}
                ::pcp/available-data  {:db/id {}}
                ::eql/query           [(list :release/script {:foo "bar"})]})

             {::pcp/nodes             {1 {::pco/name             'dynamic-resolver
                                          ::pcp/node-id          1
                                          ::pcp/requires         {:release/script {}}
                                          ::pcp/input            {:db/id {}}
                                          ::pcp/source-for-attrs #{:release/script}
                                          ::pcp/params           {:foo "bar"}
                                          ::pcp/foreign-ast      {:children [{:dispatch-key :release/script
                                                                              :key          :release/script
                                                                              :params       {:foo "bar"}
                                                                              :type         :prop}]
                                                                  :type     :root}}}
              ::pcp/index-syms        {'dynamic-resolver #{1}}
              ::pcp/unreachable-syms  #{}
              ::pcp/unreachable-attrs #{}
              ::pcp/root              1
              ::pcp/index-attrs       {:release/script 1}}))))

  (testing "optimize multiple calls"
    (is (= (compute-run-graph
             (-> {::pci/index-resolvers {'dynamic-resolver
                                         {::pco/name              'dynamic-resolver
                                          ::pco/cache?            false
                                          ::pco/dynamic-resolver? true
                                          ::pco/resolve           (fn [_ _])}}
                  ::pci/index-oir       {:release/script {#{:db/id} #{'dynamic-resolver}}
                                         :label/type     {#{:db/id} #{'dynamic-resolver}}}
                  ::eql/query           [:release/script :label/type]
                  ::pcp/available-data  {:db/id {}}}))

           {::pcp/nodes             {1 {::pco/name             'dynamic-resolver
                                        ::pcp/node-id          1
                                        ::pcp/requires         {:release/script {} :label/type {}}
                                        ::pcp/input            {:db/id {}}
                                        ::pcp/source-for-attrs #{:release/script :label/type}
                                        ::pcp/foreign-ast      (eql/query->ast [:release/script :label/type])}}
            ::pcp/index-syms        {'dynamic-resolver #{1}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/index-attrs       {:release/script 1 :label/type 1}
            ::pcp/root              1})))

  (testing "optimized with dependencies"
    (is (= (compute-run-graph
             (-> {::pci/index-resolvers {'dynamic-resolver
                                         {::pco/name              'dynamic-resolver
                                          ::pco/cache?            false
                                          ::pco/dynamic-resolver? true
                                          ::pco/resolve           (fn [_ _])}}
                  ::pci/index-oir       {:release/script {#{:db/id} #{'dynamic-resolver}}
                                         :label/type     {#{:db/id} #{'dynamic-resolver}}}
                  ::eql/query           [:release/script :label/type]
                  ::resolvers           [{::pco/name   'id
                                          ::pco/output [:db/id]}]}))

           {::pcp/nodes             {2 {::pco/name             'id
                                        ::pcp/node-id          2
                                        ::pcp/requires         {:db/id {}}
                                        ::pcp/input            {}
                                        ::pcp/source-for-attrs #{:db/id}
                                        ::pcp/run-next         3}
                                     3 {::pco/name             'dynamic-resolver
                                        ::pcp/node-id          3
                                        ::pcp/requires         {:label/type {} :release/script {}}
                                        ::pcp/input            {:db/id {}}
                                        ::pcp/source-for-attrs #{:release/script :label/type}
                                        ::pcp/after-nodes      #{2}
                                        ::pcp/foreign-ast      (eql/query->ast [:label/type :release/script])}}
            ::pcp/index-syms        '{dynamic-resolver #{3} id #{2}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/index-attrs       {:db/id 2 :release/script 3 :label/type 3}
            ::pcp/root              2})))

  (testing "chained calls"
    (is (= (compute-run-graph
             (-> {::pci/index-resolvers {'dynamic-resolver
                                         {::pco/name              'dynamic-resolver
                                          ::pco/cache?            false
                                          ::pco/dynamic-resolver? true
                                          ::pco/resolve           (fn [_ _])}}
                  ::pci/index-oir       {:a {#{} #{'dynamic-resolver}}
                                         :b {#{:a} #{'dynamic-resolver}}}
                  ::eql/query           [:b]}))

           {::pcp/nodes             {2 {::pco/name             'dynamic-resolver
                                        ::pcp/node-id          2
                                        ::pcp/requires         {:a {} :b {}}
                                        ::pcp/input            {}
                                        ::pcp/source-for-attrs #{:b :a}
                                        ::pcp/foreign-ast      (eql/query->ast [:a :b])}}
            ::pcp/index-syms        {'dynamic-resolver #{2}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/root              2
            ::pcp/index-attrs       {:a 2 :b 2}}))

    (is (= (compute-run-graph
             (-> {::pci/index-resolvers {'dynamic-resolver
                                         {::pco/name              'dynamic-resolver
                                          ::pco/cache?            false
                                          ::pco/dynamic-resolver? true
                                          ::pco/resolve           (fn [_ _])}}
                  ::pci/index-oir       {:a {#{} #{'dynamic-resolver}}
                                         :b {#{:a} #{'dynamic-resolver}}
                                         :c {#{:b} #{'dynamic-resolver}}}
                  ::eql/query           [:c]}))

           {::pcp/nodes             {3 {::pco/name             'dynamic-resolver
                                        ::pcp/node-id          3
                                        ::pcp/requires         {:a {} :b {} :c {}}
                                        ::pcp/input            {}
                                        ::pcp/source-for-attrs #{:c :b :a}
                                        ::pcp/foreign-ast      (eql/query->ast [:a :b :c])}}
            ::pcp/index-syms        '{dynamic-resolver #{3}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/root              3
            ::pcp/index-attrs       {:a 3 :b 3 :c 3}}))

    (is (= (compute-run-graph
             (-> {::pci/index-resolvers {'dynamic-resolver
                                         {::pco/name              'dynamic-resolver
                                          ::pco/cache?            false
                                          ::pco/dynamic-resolver? true
                                          ::pco/resolve           (fn [_ _])}}
                  ::resolvers           [{::pco/name   'z
                                          ::pco/output [:z]}]
                  ::pci/index-oir       {:a {#{:z} #{'dynamic-resolver}}
                                         :b {#{:a} #{'dynamic-resolver}}}
                  ::eql/query           [:b]}))

           {::pcp/nodes             {2 {::pco/name             'dynamic-resolver
                                        ::pcp/node-id          2
                                        ::pcp/requires         {:a {} :b {}}
                                        ::pcp/input            {:z {}}
                                        ::pcp/after-nodes      #{3}
                                        ::pcp/source-for-attrs #{:b :a}
                                        ::pcp/foreign-ast      (eql/query->ast [:a :b])}
                                     3 {::pco/name             'z
                                        ::pcp/node-id          3
                                        ::pcp/requires         {:z {}}
                                        ::pcp/input            {}
                                        ::pcp/source-for-attrs #{:z}
                                        ::pcp/run-next         2}}
            ::pcp/index-syms        '{dynamic-resolver #{2} z #{3}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/root              3
            ::pcp/index-attrs       {:z 3 :a 2 :b 2}}))

    (testing "chain with dynamic at start"
      (is (= (compute-run-graph
               (-> {::pci/index-resolvers {'dynamic-resolver
                                           {::pco/name              'dynamic-resolver
                                            ::pco/cache?            false
                                            ::pco/dynamic-resolver? true
                                            ::pco/resolve           (fn [_ _])}}
                    ::resolvers           [{::pco/name   'z
                                            ::pco/input  [:b]
                                            ::pco/output [:z]}]
                    ::pci/index-oir       {:a {#{} #{'dynamic-resolver}}
                                           :b {#{:a} #{'dynamic-resolver}}}
                    ::eql/query           [:z]}))

             {::pcp/nodes             {1 {::pco/name             'z
                                          ::pcp/node-id          1
                                          ::pcp/requires         {:z {}}
                                          ::pcp/input            {:b {}}
                                          ::pcp/after-nodes      #{3}
                                          ::pcp/source-for-attrs #{:z}}
                                       3 {::pco/name             'dynamic-resolver
                                          ::pcp/node-id          3
                                          ::pcp/requires         {:a {} :b {}}
                                          ::pcp/input            {}
                                          ::pcp/source-for-attrs #{:b :a}
                                          ::pcp/run-next         1
                                          ::pcp/foreign-ast      (eql/query->ast [:a :b])}}
              ::pcp/index-syms        '{z #{1} dynamic-resolver #{3}}
              ::pcp/unreachable-syms  #{}
              ::pcp/unreachable-attrs #{}
              ::pcp/root              3
              ::pcp/index-attrs       {:a 3 :b 3 :z 1}}))))

  (testing "multiple dependencies on dynamic resolver"
    (is (= (compute-run-graph
             (-> {::pci/index-resolvers {'dynamic-resolver
                                         {::pco/name              'dynamic-resolver
                                          ::pco/cache?            false
                                          ::pco/dynamic-resolver? true
                                          ::pco/resolve           (fn [_ _])}}
                  ::pci/index-oir       {:a {#{:b :c} #{'dynamic-resolver}}
                                         :b {#{} #{'dynamic-resolver}}
                                         :c {#{} #{'dynamic-resolver}}}
                  ::eql/query           [:a]}))

           {::pcp/nodes             {2 {::pco/name             'dynamic-resolver
                                        ::pcp/node-id          2
                                        ::pcp/requires         {:c {} :b {} :a {}}
                                        ::pcp/input            {}
                                        ::pcp/source-for-attrs #{:c :b :a}
                                        ::pcp/foreign-ast      (eql/query->ast [:c :b :a])}}
            ::pcp/index-syms        '{dynamic-resolver #{2}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/index-attrs       {:c 2 :b 2 :a 2}
            ::pcp/root              2})))

  (testing "multiple calls to dynamic resolver"
    (is (= (compute-run-graph
             (-> {::pci/index-resolvers {'dynamic-resolver
                                         {::pco/name              'dynamic-resolver
                                          ::pco/cache?            false
                                          ::pco/dynamic-resolver? true
                                          ::pco/resolve           (fn [_ _])}}
                  ::resolvers           [{::pco/name   'b
                                          ::pco/input  [:a]
                                          ::pco/output [:b]}]
                  ::pci/index-oir       {:a {#{} #{'dynamic-resolver}}
                                         :c {#{:b} #{'dynamic-resolver}}}
                  ::eql/query           [:c]}))

           {::pcp/nodes             {1 {::pco/name             'dynamic-resolver
                                        ::pcp/node-id          1
                                        ::pcp/requires         {:c {}}
                                        ::pcp/input            {:b {}}
                                        ::pcp/after-nodes      #{2}
                                        ::pcp/source-for-attrs #{:c}
                                        ::pcp/foreign-ast      (eql/query->ast [:c])}
                                     2 {::pco/name             'b
                                        ::pcp/node-id          2
                                        ::pcp/requires         {:b {}}
                                        ::pcp/input            {:a {}}
                                        ::pcp/run-next         1
                                        ::pcp/after-nodes      #{3}
                                        ::pcp/source-for-attrs #{:b}}
                                     3 {::pco/name             'dynamic-resolver
                                        ::pcp/node-id          3
                                        ::pcp/requires         {:a {}}
                                        ::pcp/input            {}
                                        ::pcp/run-next         2
                                        ::pcp/source-for-attrs #{:a}
                                        ::pcp/foreign-ast      (eql/query->ast [:a])}}
            ::pcp/index-syms        '{dynamic-resolver #{1 3} b #{2}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/root              3
            ::pcp/index-attrs       {:a 3 :b 2 :c 1}})))

  (testing "inner repeated dependencies"
    (is (= (compute-run-graph
             (-> {::pci/index-resolvers {'dynamic-resolver
                                         {::pco/name              'dynamic-resolver
                                          ::pco/cache?            false
                                          ::pco/dynamic-resolver? true
                                          ::pco/resolve           (fn [_ _])}}
                  ::pci/index-oir       {:release/script {#{:db/id} #{'dynamic-resolver}}
                                         :label/type     {#{:db/id} #{'dynamic-resolver}}}
                  ::eql/query           [:release/script :complex]
                  ::resolvers           [{::pco/name   'id
                                          ::pco/output [:db/id]}
                                         {::pco/name   'complex
                                          ::pco/input  [:db/id :label/type]
                                          ::pco/output [:complex]}]}))

           {::pcp/nodes             {2 {::pco/name             'id
                                        ::pcp/node-id          2
                                        ::pcp/requires         {:db/id {}}
                                        ::pcp/input            {}
                                        ::pcp/source-for-attrs #{:db/id}
                                        ::pcp/run-next         4}
                                     3 {::pco/name             'complex
                                        ::pcp/node-id          3
                                        ::pcp/requires         {:complex {}}
                                        ::pcp/input            {:label/type {} :db/id {}}
                                        ::pcp/after-nodes      #{4}
                                        ::pcp/source-for-attrs #{:complex}}
                                     4 {::pco/name             'dynamic-resolver
                                        ::pcp/node-id          4
                                        ::pcp/requires         {:label/type {} :release/script {}}
                                        ::pcp/input            {:db/id {}}
                                        ::pcp/source-for-attrs #{:release/script :label/type}
                                        ::pcp/after-nodes      #{2}
                                        ::pcp/run-next         3
                                        ::pcp/foreign-ast      (eql/query->ast [:label/type :release/script])}}
            ::pcp/index-syms        '{dynamic-resolver #{4} id #{2} complex #{3}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/index-attrs       {:db/id          2
                                     :release/script 4
                                     :label/type     4
                                     :complex        3}
            ::pcp/root              2})))

  (testing "merging long chains"
    (is (= (compute-run-graph
             (-> {::dynamics  {'dyn [{::pco/name   'a
                                      ::pco/output [:a]}
                                     {::pco/name   'a1
                                      ::pco/input  [:c]
                                      ::pco/output [:a]}
                                     {::pco/name   'a2
                                      ::pco/input  [:d]
                                      ::pco/output [:a]}
                                     {::pco/name   'b
                                      ::pco/output [:b]}
                                     {::pco/name   'b1
                                      ::pco/input  [:c]
                                      ::pco/output [:b]}
                                     {::pco/name   'c
                                      ::pco/output [:c :d]}]}
                  ::eql/query [:a :b]}))
           {::pcp/nodes             {6 {::pco/name             'dyn
                                        ::pcp/node-id          6
                                        ::pcp/requires         {:b {} :a {} :c {} :d {}}
                                        ::pcp/input            {}
                                        ::pcp/source-sym       'b
                                        ::pcp/source-for-attrs #{:c :b :d :a}
                                        ::pcp/foreign-ast      (eql/query->ast [:b :a :c :d])}}
            ::pcp/index-syms        '{dyn #{6}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/index-attrs       {:c 6 :d 6 :a 6 :b 6}
            ::pcp/root              6})))

  (testing "dynamic dependency input on local dependency and dynamic dependency"
    (is (= (compute-run-graph
             (-> {::pci/index-resolvers {'dyn {::pco/name              'dyn
                                               ::pco/cache?            false
                                               ::pco/dynamic-resolver? true
                                               ::pco/resolve           (fn [_ _])}}
                  ::pci/index-oir       {:d1 {#{:d2 :l1} #{'dyn}}
                                         :d2 {#{} #{'dyn}}}
                  ::resolvers           [{::pco/name   'l1
                                          ::pco/output [:l1]}]
                  ::eql/query           [:d1]}))

           {::pcp/nodes             {1 {::pco/name             'dyn
                                        ::pcp/node-id          1
                                        ::pcp/requires         {:d1 {}}
                                        ::pcp/input            {:d2 {} :l1 {}}
                                        ::pcp/after-nodes      #{4}
                                        ::pcp/source-for-attrs #{:d1}
                                        ::pcp/foreign-ast      (eql/query->ast [:d1])}
                                     2 {::pco/name             'dyn
                                        ::pcp/node-id          2
                                        ::pcp/requires         {:d2 {}}
                                        ::pcp/input            {}
                                        ::pcp/source-for-attrs #{:d2}
                                        ::pcp/after-nodes      #{4}
                                        ::pcp/foreign-ast      (eql/query->ast [:d2])}
                                     3 {::pco/name             'l1
                                        ::pcp/node-id          3
                                        ::pcp/requires         {:l1 {}}
                                        ::pcp/input            {}
                                        ::pcp/source-for-attrs #{:l1}
                                        ::pcp/after-nodes      #{4}}
                                     4 {::pcp/node-id  4
                                        ::pcp/requires {:l1 {} :d2 {}}
                                        ::pcp/run-and  #{3 2}
                                        ::pcp/run-next 1}}
            ::pcp/index-syms        '{dyn #{1 2} l1 #{3}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/index-attrs       {:d2 2 :l1 3 :d1 1}
            ::pcp/root              4}))))

(deftest compute-run-graph-dynamic-nested-queries-test
  (testing "simple nested query"
    (is (= (compute-run-graph
             {::pci/index-resolvers {'dyn {::pco/name             'dyn
                                           ::pco/cache?           false
                                           ::pco/dynamic-resolver? true
                                           ::pco/resolve          (fn [_ _])}
                                     'a   {::pco/name       'a
                                           ::pco/dynamic-name 'dyn
                                           ::pco/output     [{:a [:b :c]}]
                                           ::pco/provides    {:a {:b {}
                                                                 :c {}}}
                                           ::pco/resolve    (fn [_ _])}}
              ::pci/index-oir       {:a {#{} #{'a}}}
              ::eql/query           [{:a [:b]}]})
           {::pcp/nodes             {1 {::pco/name             'dyn
                                        ::pcp/node-id          1
                                        ::pcp/requires         {:a {:b {}}}
                                        ::pcp/input            {}
                                        ::pcp/source-sym       'a
                                        ::pcp/source-for-attrs #{:a}
                                        ::pcp/foreign-ast      (eql/query->ast [{:a [:b]}])}}
            ::pcp/index-syms        '{dyn #{1}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/root              1
            ::pcp/index-attrs       {:a 1}})))

  (testing "nested dependency"
    (is (= (compute-run-graph
             {::pci/index-resolvers {'dyn {::pco/name              'dyn
                                           ::pco/cache?            false
                                           ::pco/dynamic-resolver? true
                                           ::pco/resolve           (fn [_ _])}
                                     'a   {::pco/name         'a
                                           ::pco/dynamic-name 'dyn
                                           ::pco/output       [{:a [:b]}]
                                           ::pco/resolve      (fn [_ _])}}
              ::pci/index-oir       {:a {#{} #{'a}}}
              ::resolvers           [{::pco/name   'c
                                      ::pco/input  [:b]
                                      ::pco/output [:c]}]
              ::eql/query           [{:a [:c]}]})
           {::pcp/nodes             {1 {::pco/name             'dyn
                                        ::pcp/node-id          1
                                        ::pcp/requires         {:a {:b {}}}
                                        ::pcp/input            {}
                                        ::pcp/source-sym       'a
                                        ::pcp/source-for-attrs #{:a}
                                        ::pcp/foreign-ast      (eql/query->ast [{:a [:b]}])}}
            ::pcp/index-syms        '{dyn #{1}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/root              1
            ::pcp/index-attrs       {:a 1}})))

  (testing "collapse dynamic dependencies when they are from the same dynamic resolver"
    (is (= (compute-run-graph
             {::pci/index-oir       '{:local     {#{:dynamic-1} #{dynamic-1->local}}
                                      :dynamic-1 {#{} #{dynamic-constant}}
                                      :dynamic-2 {#{:dynamic-1} #{dynamic-1->dynamic-2}}}
              ::pci/index-resolvers '{dynamic-constant     {::pco/name         dynamic-constant
                                                            ::pco/input        []
                                                            ::pco/output       [:dynamic-1]
                                                            ::pco/provides     {:dynamic-1 {}}
                                                            ::pco/dynamic-name dynamic-parser-42276}
                                      dynamic-1->local     {::pco/name     dynamic-1->local
                                                            ::pco/input    [:dynamic-1]
                                                            ::pco/provides {:local {}}
                                                            ::pco/output   [:local]}
                                      dynamic-1->dynamic-2 {::pco/name         dynamic-1->dynamic-2
                                                            ::pco/input        [:dynamic-1]
                                                            ::pco/provides     {:dynamic-2 {}}
                                                            ::pco/output       [:dynamic-2]
                                                            ::pco/dynamic-name dynamic-parser-42276}
                                      dynamic-parser-42276 {::pco/name              dynamic-parser-42276
                                                            ::pco/cache?            false
                                                            ::pco/dynamic-resolver? true}}
              ::eql/query           [:local :dynamic-2]})
           '{::pcp/nodes             {1 {::pco/name             dynamic-1->local
                                         ::pcp/node-id          1
                                         ::pcp/requires         {:local {}}
                                         ::pcp/input            {:dynamic-1 {}}
                                         ::pcp/source-for-attrs #{:local}
                                         ::pcp/after-nodes      #{2}}
                                      2 {::pco/name             dynamic-parser-42276
                                         ::pcp/node-id          2
                                         ::pcp/requires         {:dynamic-1 {}
                                                                 :dynamic-2 {}}
                                         ::pcp/input            {}
                                         ::pcp/foreign-ast      {:type     :root
                                                                 :children [{:type         :prop
                                                                             :dispatch-key :dynamic-1
                                                                             :key          :dynamic-1}
                                                                            {:type         :prop
                                                                             :dispatch-key :dynamic-2
                                                                             :key          :dynamic-2}]}
                                         ::pcp/source-sym       dynamic-constant
                                         ::pcp/source-for-attrs #{:dynamic-2
                                                                  :dynamic-1}
                                         ::pcp/run-next         1}}
             ::pcp/index-syms        {dynamic-1->local     #{1}
                                      dynamic-parser-42276 #{2}}
             ::pcp/unreachable-syms  #{}
             ::pcp/unreachable-attrs #{}
             ::pcp/index-attrs       {:dynamic-1 2
                                      :local     1
                                      :dynamic-2 2}
             ::pcp/root              2})))

  (testing "union queries"
    (testing "resolver has simple output"
      (is (= (compute-run-graph
               {::pci/index-resolvers {'dyn {::pco/name             'dyn
                                             ::pco/cache?           false
                                             ::pco/dynamic-resolver? true
                                             ::pco/resolve          (fn [_ _])}
                                       'a   {::pco/name       'a
                                             ::pco/dynamic-name 'dyn
                                             ::pco/output     [{:a [:b :c]}]
                                             ::pco/provides    {:a {:b {}
                                                                   :c {}}}
                                             ::pco/resolve    (fn [_ _])}}
                ::pci/index-oir       {:a {#{} #{'a}}}
                ::eql/query           [{:a {:b [:b]
                                            :c [:c]}}]})
             {::pcp/nodes             {1 {::pco/name             'dyn
                                          ::pcp/node-id          1
                                          ::pcp/requires         {:a {:b {}
                                                                      :c {}}}
                                          ::pcp/input            {}
                                          ::pcp/source-sym       'a
                                          ::pcp/source-for-attrs #{:a}
                                          ::pcp/foreign-ast      (eql/query->ast [{:a [:b :c]}])}}
              ::pcp/index-syms        '{dyn #{1}}
              ::pcp/unreachable-syms  #{}
              ::pcp/unreachable-attrs #{}
              ::pcp/root              1
              ::pcp/index-attrs       {:a 1}})))

    #_(testing "resolver has union output"
        (is (= (compute-run-graph
                 {::pci/index-resolvers {'dyn {::pco/name             'dyn
                                               ::pco/cache?           false
                                               ::pco/dynamic-resolver? true
                                               ::pco/resolve          (fn [_ _])}
                                         'a   {::pco/name       'a
                                               ::pco/dynamic-name 'dyn
                                               ::pco/output     [{:a {:b [:b]
                                                                      :c [:c]}}]
                                               ::pco/provides    {:a {:b          {}
                                                                     :c          {}
                                                                     ::pco/unions {:b {:b {}}
                                                                                  :c {:c {}}}}}
                                               ::pco/resolve    (fn [_ _])}}
                  ::pci/index-oir       {:a {#{} #{'a}}}
                  ::eql/query           [{:a {:b [:b]
                                              :c [:c]}}]})
               {::pcp/nodes             {1 {::pco/name             'dyn
                                            ::pcp/node-id          1
                                            ::pcp/requires         {:a {:b {}}}
                                            ::pcp/input            {}
                                            ::pcp/source-sym       'a
                                            ::pcp/source-for-attrs #{:a}
                                            ::pcp/foreign-ast      (eql/query->ast [{:a {:b [:b]
                                                                                         :c [:c]}}])}}
                ::pcp/index-syms        '{dyn #{1}}
                ::pcp/unreachable-syms  #{}
                ::pcp/unreachable-attrs #{}
                ::pcp/root              1
                ::pcp/index-attrs       {:a 1}}))))

  (testing "deep nesting"
    (is (= (compute-run-graph
             {::pci/index-resolvers {'dyn {::pco/name             'dyn
                                           ::pco/cache?           false
                                           ::pco/dynamic-resolver? true
                                           ::pco/resolve          (fn [_ _])}
                                     'a   {::pco/name       'a
                                           ::pco/dynamic-name 'dyn
                                           ::pco/output     [{:a [{:b [:c]}]}]
                                           ::pco/resolve    (fn [_ _])}}
              ::pci/index-oir       {:a {#{} #{'a}}}
              ::eql/query           [{:a [{:b [:c :d]}]}]})
           {::pcp/nodes             {1 {::pco/name             'dyn
                                        ::pcp/node-id          1
                                        ::pcp/requires         {:a {:b {:c {}}}}
                                        ::pcp/input            {}
                                        ::pcp/source-sym       'a
                                        ::pcp/source-for-attrs #{:a}
                                        ::pcp/foreign-ast      (eql/query->ast [{:a [{:b [:c]}]}])}}
            ::pcp/index-syms        '{dyn #{1}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/root              1
            ::pcp/index-attrs       {:a 1}}))

    (testing "with dependency"
      (is (= (compute-run-graph
               {::pci/index-resolvers {'dyn {::pco/name             'dyn
                                             ::pco/cache?           false
                                             ::pco/dynamic-resolver? true
                                             ::pco/resolve          (fn [_ _])}
                                       'a   {::pco/name       'a
                                             ::pco/dynamic-name 'dyn
                                             ::pco/output     [{:a [{:b [:c]}]}]
                                             ::pco/resolve    (fn [_ _])}}
                ::pci/index-oir       {:a {#{} #{'a}}
                                       :d {#{:c} #{'d}}}
                ::eql/query           [{:a [{:b [:d]}]}]})
             {::pcp/nodes             {1 {::pco/name             'dyn
                                          ::pcp/node-id          1
                                          ::pcp/requires         {:a {:b {:c {}}}}
                                          ::pcp/input            {}
                                          ::pcp/source-sym       'a
                                          ::pcp/source-for-attrs #{:a}
                                          ::pcp/foreign-ast      (eql/query->ast [{:a [{:b [:c]}]}])}}
              ::pcp/index-syms        '{dyn #{1}}
              ::pcp/unreachable-syms  #{}
              ::pcp/unreachable-attrs #{}
              ::pcp/root              1
              ::pcp/index-attrs       {:a 1}}))))

  (testing "only returns the deps from the dynamic resolver in the child requirements"
    (is (= (compute-run-graph
             {::pci/index-resolvers {'dyn {::pco/name              'dyn
                                           ::pco/cache?            false
                                           ::pco/dynamic-resolver? true
                                           ::pco/resolve           (fn [_ _])}
                                     'a   {::pco/name         'a
                                           ::pco/dynamic-name 'dyn
                                           ::pco/output       [{:a [:b]}]
                                           ::pco/resolve      (fn [_ _])}
                                     'c   {::pco/name         'c
                                           ::pco/dynamic-name 'dyn
                                           ::pco/input        [:b]
                                           ::pco/output       [:c]
                                           ::pco/resolve      (fn [_ _])}}
              ::pci/index-oir       {:a {#{} #{'a}}
                                     :c {#{:b} #{'c}}}
              ::eql/query           [{:a [:c]}]})
           {::pcp/nodes             {1 {::pco/name             'dyn
                                        ::pcp/node-id          1
                                        ::pcp/requires         {:a {:c {}}}
                                        ::pcp/input            {}
                                        ::pcp/source-sym       'a
                                        ::pcp/source-for-attrs #{:a}
                                        ::pcp/foreign-ast      (eql/query->ast [{:a [:c]}])}}
            ::pcp/index-syms        '{dyn #{1}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/root              1
            ::pcp/index-attrs       {:a 1}}))

    (is (= (compute-run-graph
             {::pci/index-resolvers {'dyn {::pco/name             'dyn
                                           ::pco/cache?           false
                                           ::pco/dynamic-resolver? true
                                           ::pco/resolve          (fn [_ _])}
                                     'a   {::pco/name       'a
                                           ::pco/dynamic-name 'dyn
                                           ::pco/output     [{:a [:b]}]
                                           ::pco/resolve    (fn [_ _])}}
              ::pci/index-oir       '{:a {#{} #{a}}
                                      :c {#{:b} #{c}}
                                      :d {#{} #{c}}}
              ::eql/query           [{:a [:c :d]}]})
           {::pcp/nodes             {1 {::pco/name             'dyn
                                        ::pcp/node-id          1
                                        ::pcp/requires         {:a {:b {}}}
                                        ::pcp/input            {}
                                        ::pcp/source-sym       'a
                                        ::pcp/source-for-attrs #{:a}
                                        ::pcp/foreign-ast      (eql/query->ast [{:a [:b]}])}}
            ::pcp/index-syms        '{dyn #{1}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/root              1
            ::pcp/index-attrs       {:a 1}})))

  (testing "indirect dependencies don't need to be in the query"
    (is (= (compute-run-graph
             {::pci/index-resolvers {'dyn {::pco/name             'dyn
                                           ::pco/cache?           false
                                           ::pco/dynamic-resolver? true
                                           ::pco/resolve          (fn [_ _])}
                                     'a   {::pco/name       'a
                                           ::pco/dynamic-name 'dyn
                                           ::pco/output     [{:a [:b]}]
                                           ::pco/resolve    (fn [_ _])}}
              ::pci/index-oir       '{:a {#{} #{a}}
                                      :c {#{:b} #{c}}
                                      :d {#{} #{d}}
                                      :e {#{:d} #{a}}}
              ::eql/query           [{:a [:c :e]}]})
           {::pcp/nodes             {1 {::pco/name             'dyn
                                        ::pcp/node-id          1
                                        ::pcp/requires         {:a {:b {}}}
                                        ::pcp/input            {}
                                        ::pcp/source-sym       'a
                                        ::pcp/source-for-attrs #{:a}
                                        ::pcp/foreign-ast      (eql/query->ast [{:a [:b]}])}}
            ::pcp/index-syms        '{dyn #{1}}
            ::pcp/unreachable-syms  #{}
            ::pcp/unreachable-attrs #{}
            ::pcp/root              1
            ::pcp/index-attrs       {:a 1}}))))

(deftest root-execution-node?-test
  (is (= (pcp/root-execution-node?
           {::pcp/nodes {}}
           1)
         true))
  (is (= (pcp/root-execution-node?
           {::pcp/nodes {1 {::pcp/after-nodes #{2}}
                         2 {::pcp/run-and #{1}}}}
           1)
         true))
  (is (= (pcp/root-execution-node?
           {::pcp/nodes {1 {::pcp/after-nodes #{2}}}}
           1)
         false)))

(deftest compute-root-branch-test
  (testing "set root when no root is the current"
    (is (= (pcp/compute-root-or
             {::pcp/nodes {1 {::pcp/node-id  1
                              ::pco/name     'a
                              ::pcp/requires {:a {}}}}}
             (base-graph-env)
             {::pcp/node-id 1})
           {::pcp/root  1
            ::pcp/nodes {1 {::pcp/node-id  1
                            ::pco/name     'a
                            ::pcp/requires {:a {}}}}})))

  (testing "do nothing if there is no next node"
    (is (= (pcp/compute-root-or
             {::pcp/nodes {1 {::pcp/node-id  1
                              ::pco/name     'a
                              ::pcp/requires {:a {}}}}
              ::pcp/root  1}
             (base-graph-env)
             nil) ; nil node

           (pcp/compute-root-or
             {::pcp/nodes {1 {::pcp/node-id  1
                              ::pco/name     'a
                              ::pcp/requires {:a {}}}}
              ::pcp/root  1}
             (base-graph-env)
             {::pcp/node-id 2}) ; id not present

           {::pcp/root  1
            ::pcp/nodes {1 {::pcp/node-id  1
                            ::pco/name     'a
                            ::pcp/requires {:a {}}}}})))

  (testing "merge nodes with same sym"
    (is (= (pcp/compute-root-or
             {::pcp/nodes      {1 {::pcp/node-id  1
                                   ::pco/name     'a
                                   ::pcp/requires {:a {}}}
                                2 {::pcp/node-id  2
                                   ::pco/name     'a
                                   ::pcp/requires {:b {}}}}
              ::pcp/index-syms '{a #{1 2}}
              ::pcp/root       2}
             (assoc (base-graph-env) ::pcp/id-counter (atom 2))
             {::pcp/node-id 1})

           '{::pcp/root       1
             ::pcp/index-syms {a #{1}}
             ::pcp/nodes      {1 {::pcp/node-id  1
                                  ::pco/name     a
                                  ::pcp/requires {:a {}
                                                  :b {}}}}})))

  (testing "create new or runner"
    (is (= (pcp/compute-root-or
             {::pcp/root  1
              ::pcp/nodes {1 {::pcp/node-id  1
                              ::pco/name     'a
                              ::pcp/requires {:a {}}}
                           2 {::pcp/node-id  2
                              ::pco/name     'a2
                              ::pcp/requires {:a {}}}}}
             (assoc (base-graph-env) ::pcp/id-counter (atom 2)
               ::pspec/attribute :a)
             {::pcp/node-id 2})
           {::pcp/root  3
            ::pcp/nodes {1 {::pcp/node-id     1
                            ::pcp/after-nodes #{3}
                            ::pco/name        'a
                            ::pcp/requires    {:a {}}}
                         2 {::pcp/node-id     2
                            ::pcp/after-nodes #{3}
                            ::pco/name        'a2
                            ::pcp/requires    {:a {}}}
                         3 {::pcp/node-id  3
                            ::pcp/run-or   #{1 2}
                            ::pcp/requires {:a {}}}}}))

    (testing "with run-next"
      (is (= (pcp/compute-root-or
               {::pcp/root  2
                ::pcp/nodes {2 {::pcp/node-id  2
                                ::pco/name     'a
                                ::pcp/requires {:a {}}
                                ::pcp/run-next 1}
                             3 {::pcp/node-id  3
                                ::pco/name     'a2
                                ::pcp/requires {:a {}}
                                ::pcp/run-next 1}}}
               (assoc (base-graph-env) ::pcp/id-counter (atom 3)
                 ::pspec/attribute :a
                 ::pci/index-resolvers {'a  {::pco/provides {:a {}}}
                                        'a2 {::pco/provides {:a {}}}})
               {::pcp/node-id 3})
             '{::pcp/root  4
               ::pcp/nodes {2 {::pcp/node-id     2
                               ::pco/name        a
                               ::pcp/requires    {:a {}}
                               ::pcp/after-nodes #{4}}
                            3 {::pcp/node-id     3
                               ::pco/name        a2
                               ::pcp/requires    {:a {}}
                               ::pcp/after-nodes #{4}}
                            4 {::pcp/node-id  4
                               ::pcp/requires {:a {}}
                               ::pcp/run-or   #{2 3}
                               ::pcp/run-next 1}}}))

      (testing "don't optimize when run next is different"
        (is (= (pcp/compute-root-or
                 {::pcp/root  2
                  ::pcp/nodes {2 {::pcp/node-id  2
                                  ::pco/name     'a
                                  ::pcp/requires {:a {}}
                                  ::pcp/run-next 1}
                               3 {::pcp/node-id  3
                                  ::pco/name     'a2
                                  ::pcp/requires {:a {}}
                                  ::pcp/run-next 10}}}
                 (assoc (base-graph-env) ::pcp/id-counter (atom 3)
                   ::pspec/attribute :a)
                 {::pcp/node-id 3})
               {::pcp/root  4
                ::pcp/nodes {2 {::pcp/node-id     2
                                ::pcp/after-nodes #{4}
                                ::pco/name        'a
                                ::pcp/requires    {:a {}}
                                ::pcp/run-next    1}
                             3 {::pcp/node-id     3
                                ::pcp/after-nodes #{4}
                                ::pco/name        'a2
                                ::pcp/requires    {:a {}}
                                ::pcp/run-next    10}
                             4 {::pcp/node-id  4
                                ::pcp/run-or   #{2 3}
                                ::pcp/requires {:a {}}}}})))))

  (testing "add to the runner"
    (is (= (pcp/compute-root-or
             {::pcp/root  3
              ::pcp/nodes {1 {::pcp/node-id  1
                              ::pco/name     'a
                              ::pcp/requires {:a {}}}
                           2 {::pcp/node-id  2
                              ::pco/name     'a2
                              ::pcp/requires {:a {}}}
                           3 {::pcp/node-id  3
                              ::pcp/run-or   #{1 2}
                              ::pcp/requires {:a {}}}
                           4 {::pcp/node-id  4
                              ::pco/name     'a3
                              ::pcp/requires {:a {}}}}}
             (base-graph-env)
             {::pcp/node-id 4})
           {::pcp/root  3
            ::pcp/nodes {1 {::pcp/node-id  1
                            ::pco/name     'a
                            ::pcp/requires {:a {}}}
                         2 {::pcp/node-id  2
                            ::pco/name     'a2
                            ::pcp/requires {:a {}}}
                         3 {::pcp/node-id  3
                            ::pcp/run-or   #{1 2 4}
                            ::pcp/requires {:a {}}}
                         4 {::pcp/node-id     4
                            ::pcp/after-nodes #{3}
                            ::pco/name        'a3
                            ::pcp/requires    {:a {}}}}}))

    (testing "collapse when symbol is already there"
      (is (= (pcp/compute-root-or
               {::pcp/root       3
                ::pcp/index-syms {'a #{1 2 4}}
                ::pcp/nodes      {1 {::pcp/node-id  1
                                     ::pco/name     'a
                                     ::pcp/requires {:a {}}
                                     }
                                  2 {::pcp/node-id  2
                                     ::pco/name     'a2
                                     ::pcp/requires {:a {}}}
                                  3 {::pcp/node-id  3
                                     ::pcp/run-or   #{1 2}
                                     ::pcp/requires {:a {}}}
                                  4 {::pcp/node-id  4
                                     ::pco/name     'a
                                     ::pcp/requires {:b {}}}}}

               (base-graph-env)
               {::pcp/node-id 4})
             '{::pcp/root       3
               ::pcp/index-syms {a #{1 2}}
               ::pcp/nodes      {1
                                 {::pcp/node-id  1
                                  ::pco/name     a
                                  ::pcp/requires {:a {} :b {}}}
                                 2
                                 {::pcp/node-id  2
                                  ::pco/name     a2
                                  ::pcp/requires {:a {}}}
                                 3
                                 {::pcp/node-id  3
                                  ::pcp/run-or   #{1 2}
                                  ::pcp/requires {:a {}}}}})))

    (testing "with run context"
      (is (= (pcp/compute-root-or
               {::pcp/root  3
                ::pcp/nodes {1 {::pcp/node-id  1
                                ::pco/name     'a
                                ::pcp/requires {:a {}}}
                             2 {::pcp/node-id  2
                                ::pco/name     'a2
                                ::pcp/requires {:a {}}}
                             3 {::pcp/node-id  3
                                ::pcp/run-or   #{1 2}
                                ::pcp/requires {:a {}}
                                ::pcp/run-next 10}
                             4 {::pcp/node-id  4
                                ::pco/name     'a3
                                ::pcp/requires {:a {}}
                                ::pcp/run-next 10}}}
               (assoc (base-graph-env)
                 ::pci/index-resolvers {'a3 {::pco/provides {:a {}}}})
               {::pcp/node-id 4})
             {::pcp/root  3
              ::pcp/nodes {1 {::pcp/node-id  1
                              ::pco/name     'a
                              ::pcp/requires {:a {}}}
                           2 {::pcp/node-id  2
                              ::pco/name     'a2
                              ::pcp/requires {:a {}}}
                           3 {::pcp/node-id  3
                              ::pcp/run-or   #{1 2 4}
                              ::pcp/requires {:a {}}
                              ::pcp/run-next 10}
                           4 {::pcp/node-id     4
                              ::pcp/after-nodes #{3}
                              ::pco/name        'a3
                              ::pcp/requires    {:a {}}}}})))))

(deftest collapse-and-nodes-test
  (is (= (pcp/collapse-and-nodes
           '{::pcp/nodes {1 {::pcp/run-and #{2 3}}
                          2 {::pcp/after-nodes #{1}}
                          3 {::pcp/after-nodes #{1}}
                          4 {::pcp/run-and #{5 6}}
                          5 {::pcp/after-nodes #{4}}
                          6 {::pcp/after-nodes #{4}}}}
           1
           4)
         {::pcp/nodes {1 {::pcp/run-and #{6 3 2 5}}
                       2 {::pcp/after-nodes #{1}}
                       3 {::pcp/after-nodes #{1}}
                       5 {::pcp/after-nodes #{1}}
                       6 {::pcp/after-nodes #{1}}}}))

  (testing "can merge when target node contains run-next and branches are the same"
    (is (= (pcp/collapse-and-nodes
             '{::pcp/nodes {1 {::pcp/run-and  #{2 3}
                               ::pcp/run-next 7}
                            2 {::pcp/after-nodes #{1}}
                            3 {::pcp/after-nodes #{1}}
                            4 {::pcp/run-and #{2 3}}
                            7 {::pcp/node-id 7}}}
             1
             4)
           {::pcp/nodes {1 {::pcp/run-and #{3 2} ::pcp/run-next 7}
                         2 {::pcp/after-nodes #{1}}
                         3 {::pcp/after-nodes #{1}}
                         7 {::pcp/node-id 7}}})))

  (testing "can merge when node contains run-next and branches are the same"
    (is (= (pcp/collapse-and-nodes
             '{::pcp/nodes {1 {::pcp/run-and #{2 3}}
                            2 {::pcp/after-nodes #{1}}
                            3 {::pcp/after-nodes #{1}}
                            4 {::pcp/run-and  #{2 3}
                               ::pcp/run-next 7}
                            7 {::pcp/node-id 7}}}
             1
             4)
           {::pcp/nodes {1 {::pcp/run-and  #{2 3}
                            ::pcp/run-next 7}
                         2 {::pcp/after-nodes #{1}}
                         3 {::pcp/after-nodes #{1}}
                         7 {::pcp/node-id 7}}})))

  (testing "can merge when both nodes run-next are the same"
    (is (= (pcp/collapse-and-nodes
             '{::pcp/nodes {1 {::pcp/run-and  #{2 3}
                               ::pcp/run-next 7}
                            2 {::pcp/after-nodes #{1}}
                            3 {::pcp/after-nodes #{1}}
                            4 {::pcp/run-and  #{5 6}
                               ::pcp/run-next 7}
                            5 {::pcp/after-nodes #{4}}
                            6 {::pcp/after-nodes #{4}}
                            7 {::pcp/node-id 7}}}
             1
             4)
           {::pcp/nodes {1 {::pcp/run-and  #{6 3 2 5}
                            ::pcp/run-next 7}
                         2 {::pcp/after-nodes #{1}}
                         3 {::pcp/after-nodes #{1}}
                         5 {::pcp/after-nodes #{1}}
                         6 {::pcp/after-nodes #{1}}
                         7 {::pcp/node-id 7}}})))

  (testing "transfer after nodes"
    (is (= (pcp/collapse-and-nodes
             '{::pcp/nodes {1 {::pcp/run-and #{2 3}}
                            2 {::pcp/after-nodes #{1}}
                            3 {::pcp/after-nodes #{1}}
                            4 {::pcp/run-and     #{5 6}
                               ::pcp/after-nodes #{8}}
                            5 {::pcp/after-nodes #{4}}
                            6 {::pcp/after-nodes #{4}}
                            7 {::pcp/node-id 7}
                            8 {::pcp/run-next 4}}}
             1
             4)
           {::pcp/nodes {1 {::pcp/run-and     #{6 3 2 5}
                            ::pcp/after-nodes #{8}}
                         2 {::pcp/after-nodes #{1}}
                         3 {::pcp/after-nodes #{1}}
                         5 {::pcp/after-nodes #{1}}
                         6 {::pcp/after-nodes #{1}}
                         7 {::pcp/node-id 7}
                         8 {::pcp/run-next 1}}})))

  (testing "trigger an error if try to run with different run-next values"
    (is (thrown?
          #?(:clj AssertionError :cljs js/Error)
          (pcp/collapse-and-nodes
            '{::pcp/nodes {1 {::pcp/run-and  #{2 3}
                              ::pcp/run-next 7}
                           2 {::pcp/after-nodes #{1}}
                           3 {::pcp/after-nodes #{1}}
                           4 {::pcp/run-and  #{5 6}
                              ::pcp/run-next 8}
                           5 {::pcp/after-nodes #{4}}
                           6 {::pcp/after-nodes #{4}}
                           7 {::pcp/node-id 7}
                           8 {::pcp/node-id 8}}}
            1
            4)))))

(deftest direct-ancestor-chain-test
  (testing "return self on edge"
    (is (= (pcp/node-direct-ancestor-chain
             {::pcp/nodes {1 {}}}
             1)
           [1])))

  (testing "follow single node"
    (is (= (pcp/node-direct-ancestor-chain
             {::pcp/nodes {1 {::pcp/after-nodes #{2}}}}
             1)
           [2 1]))))

(deftest find-first-ancestor-test
  (testing "return self on edge"
    (is (= (pcp/find-first-ancestor
             {::pcp/nodes {1 {}}}
             1)
           1)))

  (testing "follow single node"
    (is (= (pcp/find-first-ancestor
             {::pcp/nodes {1 {::pcp/after-nodes #{2}}}}
             1)
           2)))

  (testing "dont end on and-nodes"
    (is (= (pcp/find-first-ancestor
             {::pcp/nodes {1 {::pcp/after-nodes #{2}}
                           2 {::pcp/run-and #{}}}}
             1)
           1)))

  (testing "jump and nodes if there is a singular node after"
    (is (= (pcp/find-first-ancestor
             {::pcp/nodes {1 {::pcp/after-nodes #{2}}
                           2 {::pcp/run-and     #{}
                              ::pcp/after-nodes #{3}}
                           3 {}}}
             1)
           3))
    (is (= (pcp/find-first-ancestor
             {::pcp/nodes {1 {::pcp/after-nodes #{2}}
                           2 {::pcp/run-and     #{}
                              ::pcp/after-nodes #{3}}
                           3 {::pcp/after-nodes #{4}}
                           4 {::pcp/run-and #{}}}}
             1)
           3))))

(deftest same-resolver-test
  (is (= (pcp/same-resolver?
           {::pco/name 'a}
           {::pco/name 'a})
         true))

  (is (= (pcp/same-resolver?
           {::pco/name 'b}
           {::pco/name 'a})
         false))

  (is (= (pcp/same-resolver?
           {}
           {})
         false)))

(deftest node-ancestors-test
  (is (= (pcp/node-ancestors
           '{::pcp/nodes {1 {::pcp/node-id 1}
                          2 {::pcp/after-nodes #{1}}}}
           2)
         [2 1]))

  (is (= (pcp/node-ancestors
           '{::pcp/nodes {1 {::pcp/node-id 1}
                          2 {::pcp/after-nodes #{1}}
                          3 {::pcp/after-nodes #{2}}}}
           3)
         [3 2 1]))

  (is (= (pcp/node-ancestors
           '{::pcp/nodes {1 {::pcp/node-id 1}
                          2 {::pcp/node-id 2}
                          3 {::pcp/node-id 3}
                          4 {::pcp/after-nodes #{2 1}}
                          5 {::pcp/after-nodes #{3}}
                          6 {::pcp/after-nodes #{5 4}}}}
           6)
         [6 4 5 1 2 3])))

(deftest node-successors-test
  (testing "leaf"
    (is (= (pcp/node-successors
             '{::pcp/nodes {1 {}}}
             1)
           [1])))

  (testing "chains"
    (is (= (pcp/node-successors
             '{::pcp/nodes {1 {::pcp/run-next 2}
                            2 {}}}
             1)
           [1 2]))

    (is (= (pcp/node-successors
             '{::pcp/nodes {1 {::pcp/run-next 2}
                            2 {::pcp/run-next 3}
                            3 {}}}
             1)
           [1 2 3])))

  (testing "branches"
    (is (= (pcp/node-successors
             '{::pcp/nodes {1 {::pcp/run-and #{2 3}}
                            2 {}
                            3 {}}}
             1)
           [1 3 2]))

    (is (= (pcp/node-successors
             '{::pcp/nodes {1 {::pcp/run-or #{2 3}}
                            2 {}
                            3 {}}}
             1)
           [1 3 2]))

    (is (= (pcp/node-successors
             '{::pcp/nodes {1 {::pcp/run-and #{2 3 4 5}}
                            2 {}
                            3 {}
                            4 {}
                            5 {}}}
             1)
           [1 4 3 2 5])))

  (testing "branch and chains"
    (is (= (pcp/node-successors
             '{::pcp/nodes {1 {::pcp/run-and  #{2 3}
                               ::pcp/run-next 4}
                            2 {}
                            3 {}
                            4 {}}}
             1)
           [1 3 2 4]))))

(deftest first-common-ancestor-test
  (is (= (pcp/first-common-ancestor
           '{::pcp/nodes {1 {::pcp/run-and #{2 3}}
                          2 {::pcp/after-nodes #{1}}
                          3 {::pcp/after-nodes #{1}}}}
           #{2 3})
         1))

  (is (= (pcp/first-common-ancestor
           '{::pcp/nodes {1 {::pcp/run-and #{2 3}}
                          2 {::pcp/after-nodes #{1 4}}
                          3 {::pcp/after-nodes #{1}}
                          4 {}}}
           #{2 3})
         1))

  (testing "single node returns itself"
    (is (= (pcp/first-common-ancestor
             '{::pcp/nodes {1 {::pcp/run-and #{2 3}}
                            2 {::pcp/after-nodes #{1 4}}
                            3 {::pcp/after-nodes #{1}}
                            4 {}}}
             #{2})
           2)))

  (testing "when nodes are on a chain, get the chain edge"
    (is (= (pcp/first-common-ancestor
             '{::pcp/nodes {1 {::pcp/run-next 2}
                            2 {::pcp/after-nodes #{1}}}}
             #{1 2})
           2)))

  #_(testing "or with internal dependency"
      (is (= (pcp/first-common-ancestor
               '{::pcp/nodes {1 {::pcp/after-nodes #{4}}
                              2 {::pcp/after-nodes #{3}}
                              3 {::pcp/after-nodes #{4}}
                              4 {::pcp/run-or #{1 3}}}}
               #{3 4})
             2))

      (is (= (pcp/first-common-ancestor
               '{::pcp/nodes {1 {::pcp/after-nodes #{5}}
                              2 {::pcp/after-nodes #{3}}
                              3 {::pcp/after-nodes #{4}}
                              4 {::pcp/after-nodes #{5}}
                              5 {::pcp/run-or #{1 4}}}}
               #{3 5})
             2))))

(deftest remove-node-test
  (testing "remove node and references"
    (is (= (pcp/remove-node
             '{::pcp/nodes      {1 {::pcp/node-id 1
                                    ::pco/name    a}}
               ::pcp/index-syms {a #{1}}}
             1)
           '{::pcp/nodes      {}
             ::pcp/index-syms {a #{}}})))

  (testing "remove after node reference from run-next"
    (is (= (pcp/remove-node
             '{::pcp/nodes      {1 {::pcp/node-id  1
                                    ::pco/name     a
                                    ::pcp/run-next 2}
                                 2 {::pcp/node-id     2
                                    ::pco/name        b
                                    ::pcp/after-nodes #{1}}}
               ::pcp/index-syms {a #{1}
                                 b #{2}}}
             1)
           '{::pcp/nodes      {2 {::pcp/node-id 2
                                  ::pco/name    b}}
             ::pcp/index-syms {a #{}
                               b #{2}}})))

  (testing "remove after node of branch nodes"
    (is (= (pcp/remove-node
             '{::pcp/nodes      {1 {::pcp/node-id     1
                                    ::pco/name        a
                                    ::pcp/after-nodes #{3}}
                                 2 {::pcp/node-id     2
                                    ::pco/name        b
                                    ::pcp/after-nodes #{3}}
                                 3 {::pcp/run-and #{1 2}}}
               ::pcp/index-syms {a #{1}
                                 b #{2}}}
             3)
           '{::pcp/nodes      {1 {::pcp/node-id 1
                                  ::pco/name    a}
                               2 {::pcp/node-id 2
                                  ::pco/name    b}}
             ::pcp/index-syms {a #{1} b #{2}}})))

  (testing "trigger error when after node references are still pointing to it"
    (is (thrown?
          #?(:clj AssertionError :cljs js/Error)
          (pcp/remove-node
            '{::pcp/nodes      {1 {::pcp/node-id     1
                                   ::pco/name        a
                                   ::pcp/after-nodes #{2}}
                                2 {::pcp/node-id  2
                                   ::pco/name     b
                                   ::pcp/run-next 1}}
              ::pcp/index-syms {a #{1}
                                b #{2}}}
            1)))))

(deftest collapse-nodes-chain-test
  (testing "merge requires and attr sources"
    (is (= (pcp/collapse-nodes-chain
             '{::pcp/nodes       {1 {::pcp/node-id          1
                                     ::pco/name             a
                                     ::pcp/requires         {:a {}}
                                     ::pcp/source-for-attrs #{:a}}
                                  2 {::pcp/node-id          2
                                     ::pco/name             a
                                     ::pcp/requires         {:b {}}
                                     ::pcp/source-for-attrs #{:b}}}
               ::pcp/index-syms  {a #{1 2}}
               ::pcp/index-attrs {:a 1 :b 2}}
             1 2)
           '{::pcp/nodes       {1 {::pcp/node-id          1
                                   ::pco/name             a
                                   ::pcp/source-for-attrs #{:a :b}
                                   ::pcp/requires         {:a {}
                                                           :b {}}}}
             ::pcp/index-syms  {a #{1}}
             ::pcp/index-attrs {:a 1 :b 1}})))

  (testing "keep input from outer most"
    (is (= (pcp/collapse-nodes-chain
             '{::pcp/nodes       {1 {::pcp/node-id          1
                                     ::pco/name             a
                                     ::pcp/input            {:x {}}
                                     ::pcp/requires         {:a {}}
                                     ::pcp/source-for-attrs #{:a}}
                                  2 {::pcp/node-id          2
                                     ::pco/name             a
                                     ::pcp/input            {:y {}}
                                     ::pcp/requires         {:b {}}
                                     ::pcp/source-for-attrs #{:b}}}
               ::pcp/index-syms  {a #{1 2}}
               ::pcp/index-attrs {:a 1 :b 2}}
             1 2)
           '{::pcp/nodes       {1 {::pcp/node-id          1
                                   ::pco/name             a
                                   ::pcp/input            {:x {}}
                                   ::pcp/source-for-attrs #{:a :b}
                                   ::pcp/requires         {:a {}
                                                           :b {}}}}
             ::pcp/index-syms  {a #{1}}
             ::pcp/index-attrs {:a 1 :b 1}})))

  (testing "pull run next"
    (is (= (pcp/collapse-nodes-chain
             '{::pcp/nodes      {1 {::pcp/node-id 1
                                    ::pco/name    a}
                                 2 {::pcp/node-id  2
                                    ::pco/name     a
                                    ::pcp/run-next 3}
                                 3 {::pcp/node-id     3
                                    ::pco/name        b
                                    ::pcp/after-nodes #{2}}}
               ::pcp/index-syms {a #{1 2}
                                 b #{3}}}
             1 2)
           '{::pcp/nodes      {1 {::pcp/node-id  1
                                  ::pco/name     a
                                  ::pcp/run-next 3}
                               3 {::pcp/node-id     3
                                  ::pco/name        b
                                  ::pcp/after-nodes #{1}}}
             ::pcp/index-syms {a #{1}
                               b #{3}}})))

  (testing "move after nodes"
    (is (= (pcp/collapse-nodes-chain
             '{::pcp/nodes      {1 {::pcp/node-id 1
                                    ::pco/name    a}
                                 2 {::pcp/node-id     2
                                    ::pco/name        a
                                    ::pcp/after-nodes #{3 4}}
                                 3 {::pcp/node-id  3
                                    ::pco/name     b
                                    ::pcp/run-next 2}
                                 4 {::pcp/node-id  4
                                    ::pco/name     c
                                    ::pcp/run-next 2}}
               ::pcp/index-syms {a #{1 2}
                                 b #{3}
                                 c #{4}}}
             1 2)
           '{::pcp/nodes      {1 {::pcp/node-id     1
                                  ::pco/name        a
                                  ::pcp/after-nodes #{3 4}}
                               3 {::pcp/node-id  3
                                  ::pco/name     b
                                  ::pcp/run-next 1}
                               4 {::pcp/node-id  4
                                  ::pco/name     c
                                  ::pcp/run-next 1}}
             ::pcp/index-syms {a #{1}
                               b #{3}
                               c #{4}}}))))

(deftest compute-node-chain-depth-test
  (testing "simple chain"
    (is (= (pcp/compute-node-chain-depth
             {::pcp/nodes {1 {}}}
             1)
           {::pcp/nodes {1 {::pcp/node-chain-depth 0}}}))

    (is (= (pcp/compute-node-chain-depth
             {::pcp/nodes {1 {::pcp/node-chain-depth 42}}}
             1)
           {::pcp/nodes {1 {::pcp/node-chain-depth 42}}}))

    (is (= (pcp/compute-node-chain-depth
             {::pcp/nodes {1 {::pcp/run-next 2}
                           2 {}}}
             1)
           {::pcp/nodes {1 {::pcp/run-next         2
                            ::pcp/node-chain-depth 1}
                         2 {::pcp/node-chain-depth 0}}}))

    (is (= (pcp/compute-node-chain-depth
             {::pcp/nodes {1 {::pcp/run-next 2}
                           2 {::pcp/run-next 3}
                           3 {}}}
             1)
           {::pcp/nodes {1 {::pcp/run-next         2
                            ::pcp/node-chain-depth 2}
                         2 {::pcp/run-next         3
                            ::pcp/node-chain-depth 1}
                         3 {::pcp/node-chain-depth 0}}})))

  (testing "branches chain"
    (is (= (pcp/compute-node-chain-depth
             {::pcp/nodes {1 {::pcp/run-and #{2 3}}
                           2 {}
                           3 {}}}
             1)
           {::pcp/nodes {1 {::pcp/run-and           #{2 3}
                            ::pcp/node-chain-depth  1
                            ::pcp/node-branch-depth 1}
                         2 {::pcp/node-chain-depth 0}
                         3 {::pcp/node-chain-depth 0}}}))

    (is (= (pcp/compute-node-chain-depth
             {::pcp/nodes {1 {::pcp/run-and  #{2 3}
                              ::pcp/run-next 4}
                           2 {}
                           3 {}
                           4 {}}}
             1)
           {::pcp/nodes {1 {::pcp/run-and           #{2 3}
                            ::pcp/run-next          4
                            ::pcp/node-chain-depth  2
                            ::pcp/node-branch-depth 1}
                         2 {::pcp/node-chain-depth 0}
                         3 {::pcp/node-chain-depth 0}
                         4 {::pcp/node-chain-depth 0}}}))))

(deftest compute-node-depth-test
  (is (= (pcp/compute-node-depth
           {::pcp/nodes {1 {}}}
           1)
         {::pcp/nodes {1 {::pcp/node-depth 0}}}))

  (is (= (pcp/compute-node-depth
           {::pcp/nodes {1 {::pcp/after-nodes #{2}}
                         2 {}}}
           1)
         {::pcp/nodes {1 {::pcp/after-nodes #{2} ::pcp/node-depth 1}
                       2 {::pcp/node-depth        0
                          ::pcp/node-branch-depth 0}}}))

  (is (= (pcp/compute-node-depth
           {::pcp/nodes {1 {::pcp/after-nodes #{2}}
                         2 {::pcp/after-nodes #{3}}
                         3 {}}}
           1)
         {::pcp/nodes {1 {::pcp/after-nodes #{2}
                          ::pcp/node-depth  2}
                       2 {::pcp/after-nodes       #{3}
                          ::pcp/node-depth        1
                          ::pcp/node-branch-depth 0}
                       3 {::pcp/node-depth        0
                          ::pcp/node-branch-depth 0}}}))

  (testing "in case of multiple depths, use the deepest"
    (is (= (pcp/compute-node-depth
             {::pcp/nodes {1 {::pcp/after-nodes #{2 4}}
                           2 {::pcp/after-nodes #{3}}
                           3 {}
                           4 {}}}
             1)
           {::pcp/nodes {1 {::pcp/after-nodes #{4 2}
                            ::pcp/node-depth  2}
                         2 {::pcp/after-nodes       #{3}
                            ::pcp/node-depth        1
                            ::pcp/node-branch-depth 0}
                         3 {::pcp/node-depth        0
                            ::pcp/node-branch-depth 0}
                         4 {::pcp/node-depth        0
                            ::pcp/node-branch-depth 0}}})))

  (testing "in case of run next of a branch node, it should be one more than the deepest item in the branch nodes"
    (is (= (pcp/compute-node-depth
             {::pcp/nodes {1 {::pcp/after-nodes #{2}}
                           2 {::pcp/run-next 1
                              ::pcp/run-and  #{3 4}}
                           3 {::pcp/after-nodes #{2}}
                           4 {::pcp/after-nodes #{2}}}}
             1)
           {::pcp/nodes {1 {::pcp/after-nodes #{2}
                            ::pcp/node-depth  2}
                         2 {::pcp/run-and           #{3 4}
                            ::pcp/run-next          1
                            ::pcp/node-depth        0
                            ::pcp/node-branch-depth 1}
                         3 {::pcp/after-nodes      #{2}
                            ::pcp/node-chain-depth 0}
                         4 {::pcp/after-nodes      #{2}
                            ::pcp/node-chain-depth 0}}}))))

(deftest node-depth-test
  (is (= (pcp/node-depth
           {::pcp/nodes {1 {::pcp/after-nodes #{2}}
                         2 {}}}
           1)
         1)))

(deftest compute-all-node-depths-test
  (is (= (pcp/compute-all-node-depths
           {::pcp/nodes {1 {::pcp/after-nodes #{2}}
                         2 {::pcp/after-nodes #{3}}
                         3 {}
                         4 {}
                         5 {::pcp/after-nodes #{4}}}})
         {::pcp/nodes {1 {::pcp/after-nodes #{2}
                          ::pcp/node-depth  2}
                       2 {::pcp/after-nodes       #{3}
                          ::pcp/node-branch-depth 0
                          ::pcp/node-depth        1}
                       3 {::pcp/node-branch-depth 0
                          ::pcp/node-depth        0}
                       4 {::pcp/node-depth        0
                          ::pcp/node-branch-depth 0}
                       5 {::pcp/after-nodes #{4}
                          ::pcp/node-depth  1}}})))

(deftest set-node-run-next-test
  (is (= (pcp/set-node-run-next
           {::pcp/nodes {1 {}
                         2 {}}}
           1
           2)
         {::pcp/nodes {1 {::pcp/run-next 2}
                       2 {::pcp/after-nodes #{1}}}}))

  (is (= (pcp/set-node-run-next
           {::pcp/nodes {1 {::pcp/run-next 2}
                         2 {::pcp/after-nodes #{1}}}}
           1
           nil)
         {::pcp/nodes {1 {}
                       2 {}}})))

(deftest params-conflicting-keys-test
  (is (= (pcp/params-conflicting-keys {} {})
         #{}))

  (is (= (pcp/params-conflicting-keys {:x 1} {:y 2})
         #{}))

  (is (= (pcp/params-conflicting-keys {:x 1} {:x 2})
         #{:x}))

  (is (= (pcp/params-conflicting-keys {:x 1} {:x 1})
         #{})))

(deftest graph-provides-test
  (is (= (pcp/graph-provides
           {::pcp/index-attrs {:a 1 :b 2}})
         #{:a :b})))
