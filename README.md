# bb-rename

Bulk rename files with babashka.

As a warning, this library isn't polished yet.

## Usage (subject to change)

Here is an example script using bb-rename.

```clojure
#!/usr/bin/env bb
(babashka.deps/add-deps '{:deps {io.github.nnoodle/bb-rename {:git/url "https://github.com/nnoodle/bb-rename" :sha ",,,"}}})
(require '[io.github.nnoodle.bb-rename :as mv])

(mv/rename (mv/files "~/Downloads")               ; mv/files here returns list of regular files in ~/Downloads
  #_{:dry-run? true}                              ; an optional map of options
  (mv/format :ext #"^$" :ext "%g")                ; add an extension to files without one
  ;                     ^ optional, same as the first if left out
  (mv/replace :ext #"^(?:jpeg|jfif)$" "jpg")      ; regularize jpeg extensions to jpg
  (mv/format :name #"Untitled" "%tFT%tH:%tM:%tS") ; renames Untitled files to a timestamp
  (mv/set :name (constantly true) #(str/trim (mv/access :name %)))    ; unconditionally trim all names
  (mv/substitute :ext #"^pdf$" :parent (str (fs/home) "/Documents")) ; moves pdfs to ~/Documents.

 nil ; don't print out the result of mv/rename
```

`rename` takes a list of files renames them with zero or more renaming
transducers. The renaming transducers included are `format`,
`replace`, `substitute`, and `set`. Whenever a transducer takes a
**part**, it means a portion of a **path** and the **change**. More
specifically, it means one of the following keys:

- `:old-path`
- `:old-parent`
- `:old-file`
- `:old-name`
- `:old-ext`
- `:new-path` (= `:path`)
- `:new-parent` (= `:parent`)
- `:new-file` (= `:file`)
- `:new-name` (= `:name`)
- `:new-ext` (= `:ext`)

Renaming transducers take a **change** as input and a new **change**
as output. They don't rename any files physically. A change is a map
with two keys, :old and :new, associated with the original path of the
file and the new path of the file respectively.

A **part** of a path means either the full :path, the file's :parent,
the :file's full name, the :name of the file without the extension, or
its __:ext__ension. If the name or extension is missing from the file
name, an empty string is given.

    /home/$USER/Downloads/name.png
    ------------------------------ :path
    ---------------------          :parent
                          -------- :file
                          ----     :name
                               --- :ext

## License

Copyright © 2022 Noodles!

Distributed under the ISC License.
