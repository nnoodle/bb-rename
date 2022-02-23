(ns ^{:doc "Bulk rename files with babashka."}
    io.github.nnoodle.bb-rename
  (:refer-clojure :exclude [set replace]
                  :rename {format formare
                           name nomen})
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs])
  (:import java.nio.file.Path))

(set! *warn-on-reflection* true)

;;; core functionality

(defn- remove-discriminator
  "If a file was called `foo(1).txt', remove the `(1)' bit if it exists."
  [path]
  (fs/path (fs/parent path)
           (str/replace (fs/file-name path)
                        #"\(\d+\)(\.[^.]*)?$" "$1")))

(defn- extension
  "Like babashka.fs/extension, but add a dot if it exists."
  [path]
  (if-let [ext (fs/extension path)]
    (str "." ext)
    ""))

(defn files
  "Return a list of regular files in dir(s)."
  [& dir-or-dirs]
  (->> dir-or-dirs
       (mapcat (comp fs/list-dir fs/expand-home))
       (filter fs/regular-file?)))

(defn- safely-rename
  "Rename old path to new path without overwriting other files."
  [old-path new-path]
  (if-not (fs/exists? new-path)
    (fs/move old-path new-path {:replace-existing false})
    (let [fmt (formare "%s(%%d)%s" ; assume new-path already had its discriminator removed.
                      (-> new-path fs/file-name fs/split-ext first)
                      (extension new-path))]
      (loop [discrim 1]
        (let [uniq-path (fs/path (fs/parent new-path) (formare fmt discrim))]
          (if-not (fs/exists? uniq-path)
            (fs/move old-path uniq-path {:replace-existing false})
            (recur (inc discrim))))))))

(defn transform
  "Return list of changes/renames to files transformed by transducers.
  See `rename`."
  {:style/indent 1}
  [files & transducers]
  (transduce (comp
              (map #(zipmap [:old :new] [% (remove-discriminator %)]))
              (apply comp transducers)
              (filter #(not= (:old %) (:new %))))
             conj (flatten files)))

(defn- print-change
  [c]
  (let [cwd (System/getProperty "user.dir")]
    (printf "%-25s →\t%s\n"
            (fs/relativize cwd (fs/absolutize (:old c)))
            (fs/relativize cwd (fs/absolutize (:new c)))))
  c)

(defn rename
  "Rename files using transducers.

  opts are expressed as a hashmap optionally passed in before the
  transducers.
  Supported options:
  :print? - if true, print the changes that are made.
            Defaults to true.
  :dry-run? - if true, do not actually rename files.
              Defaults to false.

  files is a list of files that are to be renamed. May be arbitrarily
  nested; it'll be `flatten`ed internally.

  transducers take a change as input and a modified change as the
  output. A change is a hashmap with two keys, :old and :new, that
  correspond to the original Path (java.nio.file.Path) and the new Path."
  {:style/indent 1}
  [files & opts+transducers]
  (let [opts (merge {:print? true
                     :dry-run? false}
                    (when (map? (first opts+transducers))
                      (first opts+transducers)))
        transducers (if (map? (first opts+transducers))
                      (rest opts+transducers)
                      opts+transducers)
        changes (apply transform files transducers)]
    (doall
     (for [c changes]
       (let [nc (if (:dry-run? opts)
                  c
                  (assoc c :new (safely-rename (:old c) (:new c))))]
         (if (:print? opts)
           (print-change nc)
           nc))))))

;;; operators (should this be in a new file?)

(defn- part
  "Select and maybe update part of path as a string, where -part is one
  of the following keywords:

  /home/$USER/Downloads/name.png
  ------------------------------ `:path`
  ---------------------          `:parent`
                        -------- `:file`
                        ----     `:name`
                             --- `:ext`

  If new is provided, return a Path with the part changed."
  ([-part ^Path path]
   (case -part
     :path   (.toString path)
     :parent (.toString ^Path (fs/parent path))
     :file   (fs/file-name path)
     :name   (-> path fs/file-name fs/split-ext first)
     :ext    (or (fs/extension path) "")))
  ([-part path new]
   (case -part
     :path   (fs/path new)
     :parent (fs/path new (fs/file-name path))
     :file   (fs/path (fs/parent path) new)
     :name   (fs/path (fs/parent path) (str/join [new (extension path)]))
     :ext    (fs/path (fs/parent path)
                      (str (part :name path) (if (str/starts-with? new ".") "" ".") new)))))

(defn- apply-if-fn [f & args]
  (if (fn? f)
    (apply f args)
    f))

(defn- split-age-part
  [k]
  (case k
    :old-path             [:old :path]
    :old-parent           [:old :parent]
    :old-file             [:old :file]
    :old-name             [:old :name]
    :old-ext              [:old :ext]
    (:new-path :path)     [:new :path]
    (:new-parent :parent) [:new :parent]
    (:new-file :file)     [:new :file]
    (:new-name :name)     [:new :name]
    (:new-ext :ext)       [:new :ext]))

(defn access
  "Get old/new part of change, or update it if x is supplied."
  ([p c]
   (let [[a p] (split-age-part p)]
     (part p (a c))))
  ([p c x]
   (let [[a p] (split-age-part p)]
     (update c :new                     ; changes are only applied on the new
             (if (fn? x)
               #(part p % (x (part p (a c))))
               #(part p % x))))))

(defn set
  "Set set-part to new if pred returns true on get-part of change.

  pred is applied on the whole change and sets the full path to new if
  get-part is not supplied.

  If new is a function, it takes the whole change as an argument and
  outputs a string."
  ([pred new]
   (map (fn [c]
          (if (pred c)
            (access :path c (apply-if-fn new c))
            c))))
  ([get-part pred new]
   (set get-part pred get-part new))
  ([get-part pred set-part new]
   (map (fn [c]
          (if (pred (access get-part c))
            (access set-part c (apply-if-fn new c))
            c)))))

(defn substitute
  "Substitute set-part with new if get-part of change matches re.

  If new is a function, it takes the whole change as an argument and
  outputs a string."
  ([get-part re new]
   (substitute get-part re get-part new))
  ([get-part re set-part new]
   (set get-part #(re-find re %) set-part new)))

(defn replace
  "Replace matched substring in part with replacement.

  If new is a function, it takes the whole change as an argument and
  outputs a string."
  [part re new]
  (map (fn [c]
         (access part c #(str/replace % re (apply-if-fn new c))))))

;;; format operator

(defn- hash-map-format
  "Format string according to format-map, which is a map of characters
  and functions that take [StringBuilder sequence]. The function
  should mutate the StringBuilder and return the rest of the sequence."
  ;; I feel like there should be a library for this already, oh well.
  [format-map ^String string & args]
  (let [builder (StringBuilder. (.length string))]
    (loop [s (seq string)]
      (cond
        (empty? s) (.toString builder)

        (= (first s) \%)
        (cond
          (nil? (second s)) (throw (Exception. (formare "Incomplete format string `%s'" string)))
          (= (second s) \%) (do (.append builder \%)
                                (recur (drop 2 s)))

          (format-map (second s))
          (recur (apply (format-map (second s))
                        builder
                        (drop 2 s)
                        args))

          :else (throw (Exception. (formare "Bad format character `%c'" (second s)))))

        :else (do (.append builder (first s))
                  (recur (rest s)))))))

(def ^:dynamic *checksum-format*
  "Default checksum format for `checksum` if none is provided.

  Other possible algorithms that may be used:
  MD2, MD5, SHA-1, SHA-256, SHA-384, and SHA-512."
  "SHA-256")

(defn- checksum
  "Acceptable algorithms that can be used:
  MD2, MD5, SHA-1, SHA-256, SHA-384, and SHA-512."
  ([path] (checksum path *checksum-format*))
  ([path algorithm]
   (with-open [stream (io/input-stream (fs/file path))]
     (let [digest (java.security.MessageDigest/getInstance algorithm)
           s (java.security.DigestInputStream. stream digest)
           bufsize (* 1024 1024)        ; 1MiB
           buf (byte-array bufsize)]
       (while (not= -1 (.read s buf 0 bufsize)))
       (apply str (map (partial formare "%02x")
                       (.digest digest)))))))

(def ^:dynamic *file-???-extension-fallback*
  "Fallback for when the file command returns \"???\" after attempting
  to guess the extension."
  ".mp4")

(defn- guess-ext
  [path]
  (let [path (str path)
        out (clojure.java.shell/sh "file" "--extension" "--" path)]
    (assert (zero? (:exit out)) (formare "exit code `file %s' not 0. With error output `%s'" path (:err out)))
    (let [ext (-> (re-find #" .*$" (:out out))
                  str/trim
                  (str/split #"/"))]
      (case (first ext)
        "(null)" ""
        "???" *file-???-extension-fallback*
        (str "." (first ext))))))

(def ^:dynamic *path-format-map*
  "A map of characters to functions.

  Each function takes three arguments, [StringBuilder, sequence,
  matches, changes], and returns the rest of the string."
  (letfn [(append-return [v f]
            (fn [^StringBuilder b s m path]
              (.append b (f (v path)))
              s))
          (group [n]
            (fn [^StringBuilder b s m path]
              (.append b (get m n))
              s))]
    {\% (append-return :new (constantly "%"))
     \e (append-return :new extension)
     \n (append-return :new #(part :name %))
     \g (append-return :old guess-ext)
     \x (append-return :old checksum)
     \t (fn [^StringBuilder b s m path]
          (.append b (formare (str "%t" (first s))
                              (-> path :old
                                  fs/last-modified-time
                                  fs/file-time->instant
                                  java.util.Date/from)))
          (rest s))
     \0 (group 0)
     \1 (group 1)
     \2 (group 2)
     \3 (group 3)
     \4 (group 4)
     \5 (group 5)
     \6 (group 6)
     \7 (group 7)
     \8 (group 8)
     \9 (group 9)}))

(defn format
  "Format set-part with fmt if get-part matches re."
  ([get-part re fmt]
   (format get-part re get-part fmt))
  ([get-part re set-part fmt]
   (map (fn [c]
          (let [m (re-matcher re (access get-part c))]
            (if (re-find m)
              (access set-part c (hash-map-format *path-format-map*
                                                  fmt
                                                  (let [m (re-groups m)]
                                                    (if (sequential? m) m [m]))
                                                  c))
              c))))))

;;; example

(comment
  (def ^:private sample-dir "testdir")

  (rename (files "~/Downloads")
    {:dry-run? true}
    (substitute :ext #"^$" #(guess-ext (:old %)))
    (replace :ext #"^jpeg|jpe?g[ :]large|jfif$" "jpg")
    (substitute :name #"^image" #(checksum (:old %) "SHA-256")))

  (rename (files "~/Downloads")
    {:dry-run? true}
    (format :file #"^[^.]+$" "%n%g") ; add an extension to files without one
    (replace :file #"\.(jpeg|jpe?g[ :]large|jfif)$" ".jpg") ; convert defective forms of jpg into its canonical form
    (format :name #"^image" "%tFT%tH:%tM:%tS")) ; replace junk names with a hash

  (rename (files sample-dir)
    {:dry-run? true}
    (replace :ext #"md" "txt")
    (format :name #"(f)(o)(o)" "%3%2%1-%tQ"))

  (rename (files sample-dir)
    (replace :ext #"txt" "md")
    ;; (replace-ext #"md" "txt")
    )

  (rename (files "some-directory")
    {:dry-run? true}
    #_(mv/transducer :get-part matcher/predicate :set-part replacement)
    (replace :ext #"^(?:jpg|jfif)$" #_:ext "jpeg") ; implicit
    (substitute :ext #"pdf" :parent (fs/expand-home "~/Documents/pdfs"))
    (set :ext #(= "sh|jar" %) :parent #(str (new-parent %) "../lib"))
    (format :name #"^image" "%tFT%tH:%tM:%tS"))

  (rename (files "~/Downloads")
    {:dry-run? true}
    (format :ext #"^$" :ext "%g")
    (replace :ext #"^(?:jpeg|jpe?g[ :]large|jfif)$" "jpg")
    (substitute :ext #"^pdf$" :parent (str (fs/home) "/Documents")))

  (rename (files "testdir")))
