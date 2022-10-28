# sql-batcher

A Clojure library designed to run large sql updates/deletes in batches

## Rationale

If an update/delete query is doing a large table scan and spanning a large number of rows, that table could be blocked for a potentially very long time. To avoid this, we run our update/delete queries in batches of relatively small sizes, based on the primary key(s) of the table to on which the updates/deletes are supposed to run. More info in this article - http://mysql.rjweb.org/doc.php/deletebig

## Usage

FIXME

## Test

```
lein test
```

## License

Copyright © 2022 [Punit Naik](https://github.com/punit-naik)

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
