(ns eponai.server.external.elastic-cloud
  (:require
    [eponai.server.log :as log]
    [eponai.server.external.host :as host]
    [com.stuartsierra.component :as component]
    [suspendable.core :as suspendable]
    [taoensso.timbre :refer [error debug]]
    [cheshire.core :as cheshire]
    [clj-time.coerce]
    [clj-time.format])
  (:import (org.elasticsearch.common.settings Settings)
           (org.elasticsearch.client.transport TransportClient)
           (org.elasticsearch.common.transport InetSocketTransportAddress)
           (java.net InetAddress Inet6Address Inet4Address UnknownHostException)
           (org.elasticsearch.action.bulk BulkResponse)
           (org.elasticsearch.xpack.client PreBuiltXPackTransportClient)))

;; xpack example:
;; https://github.com/elastic/found-shield-example

(defn- add-transport-addresses [client cluster-hostname]
  ;; ipv6 doesn't work from within docker?
  ;; so it won't work for us?
  (let [ipv6? false
        ipv4? true
        port 9343]
    (try
      (doseq [address (InetAddress/getAllByName cluster-hostname)]
        (when (or (and ipv6? (instance? Inet6Address address))
                  (and ipv4? (instance? Inet4Address address)))
          (debug "Adding transport address: " address " port: " port)
          (.addTransportAddress client (InetSocketTransportAddress. ^InetAddress address port))))
      (catch UnknownHostException e
        (error "Unable to get host: " (.getMessage e))))))

(def logstash-iso-format "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
(def iso-format-formatter (clj-time.format/formatter logstash-iso-format))
(def yyyy-MM-dd-formatter (clj-time.format/formatter "yyyy.MM.dd"))

;; This way of getting the cluster-id is taken from:
;; https://github.com/elastic/found-shield-example/blob/master/src/main/java/org/elasticsearch/cloud/transport/example/TransportExample.java#L54
(defn- ^String cluster-id-from-host [host]
  (first (.split host "\\." 2)))

(defrecord ElasticCloud [^String cluster-hostname
                         ;; shield-user being "username:password"
                         ^String xpack-user
                         ^String index-name
                         server-address]
  component/Lifecycle
  (start [this]
    (if (:client this)
      this
      (let [params [cluster-hostname xpack-user index-name]
            _ (assert (every? string? params)
                      (str "Missing required ElasticCloud parameters. Were: "
                           (zipmap [:cluster-hostname :xpack-user :index-name] params)))
            settings (-> (Settings/builder)
                         (.put "client.transport.nodes_sampler_interval" "5s")
                         (.put "client.transport.sniff", false)
                         (.put "transport.tcp.compress", true)
                         (.put "cluster.name" (cluster-id-from-host cluster-hostname))
                         (.put "xpack.security.transport.ssl.enabled" true)
                         (.put "request.headers.X-Found-Cluster", "${cluster.name}")
                         (.put "xpack.security.user" xpack-user)
                         (.build))
            client (PreBuiltXPackTransportClient. ^Settings settings [])]
        (add-transport-addresses client cluster-hostname)
        (assoc this :client client :host (host/webserver-url server-address)))))
  (stop [this]
    (when-let [c (:client this)]
      (try
        (.close ^TransportClient c)
        (catch Exception ignore)))
    (dissoc this :client :host))
  suspendable/Suspendable
  (suspend [this]
    this)
  (resume [this old-this]
    (if-let [c (:client old-this)]
      (assoc this :client c)
      (do (component/stop old-this)
          (component/start this))))

  log/IBulkLogger
  (bulk-size [this]
    ;; What's a good bulk-size?
    ;; Docs says to start with around ~1000-5000 "documents" (json objects),
    ;; or 5-15MB. I measured a server.parser/read json object, and it was 435B.
    ;; So we can do more than 1000.
    ;; https://www.elastic.co/guide/en/elasticsearch/guide/current/bulk.html
    2000)
  (log-bulk [this messages]
    (debug "Sending messages to elastic: " messages)
    (let [^TransportClient client (:client this)
          bulk-builder (.prepareBulk client)
          json-opts {:date-format logstash-iso-format}
          _ (doseq [msg messages]
              (let [date (clj-time.coerce/from-long (:millis msg))
                    ymd (clj-time.format/unparse yyyy-MM-dd-formatter date)
                    timestamp (clj-time.format/unparse iso-format-formatter date)]
                (.add bulk-builder
                      (-> (.prepareIndex client
                                         (str index-name "-" ymd)
                                         (str (:id msg)))
                          (.setSource (cheshire/generate-string
                                        (assoc (:data msg)
                                          "@timestamp" timestamp
                                          :level (:level msg)
                                          :host (:host this))
                                        json-opts))))))
          bulk-response ^BulkResponse (.get bulk-builder)]
      (when (.hasFailures bulk-response)
        (error "elastic cloud bulk request failure: " (.buildFailureMessage bulk-response))))))
