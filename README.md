# DBeaver QuerySweep Plugin

A DBeaver plugin that executes a SQL query against multiple database connections in parallel with color-coded, sortable results. Works with any DBeaver connection (Snowflake, PostgreSQL, MySQL, Oracle, …) as long as the query is valid for each target.

## Features

- **Connection grid** — Select multiple connections from your DBeaver workspace via a wrapping checkbox grid with a filter box and All/None/Refresh buttons
- **Connection filter** — Type to narrow the grid by connection name or driver name (empty shows all connections)
- **Parallel execution** — Runs the query against all selected connections concurrently (one thread per CPU core)
- **Color-coded results** — Each connection's rows are colored distinctly in the results table
- **Sortable columns** — Click any column header to sort; arrow indicator shows sort direction
- **Connection status** — Per-connection status panel with color-coded RUNNING/OK/FAIL states and elapsed time
- **Query history** — Persisted to `~/.query-sweep/dbeaver-history.txt`; access via the History button
- **Placeholders** — Define custom `${name}` variables via the Placeholders dialog, persisted to `~/.query-sweep/dbeaver-placeholders.txt`
- **Per-connection placeholders** — `${connection}` (the connection name) and `${connection_sql}` (uppercased, non-alphanumerics replaced with `_`) are substituted per connection at run time

## Building

Requires Java 21. The build compiles against JARs from a local DBeaver installation.

```bash
./gradlew build
```

The output JAR is at `build/libs/io.github.mlorek.querysweep-1.0.0.jar`.

The build auto-detects DBeaver in the standard locations — `/usr/share/dbeaver-ce/plugins` (Linux .deb), `/Applications/DBeaver.app/Contents/Eclipse/plugins` and `~/Applications/DBeaver.app/Contents/Eclipse/plugins` (macOS). If your DBeaver is installed elsewhere, pass `-PdbeaverPlugins=/path/to/dbeaver/plugins`.

## Building the update site (p2 repository)

```bash
./gradlew updateSite
```

This produces an installable p2 repository at `build/updatesite` containing the feature, the bundle, a category, and compressed p2 metadata (`content.jar`/`artifacts.jar`). It runs the p2 publisher applications that ship inside your DBeaver installation, so no extra tooling is required.

## Installation

### From the update site (recommended)

1. Build the update site (above). Optionally host the `build/updatesite` directory at any static URL (GitHub Pages works).
2. In DBeaver: **Help > Install New Software**, paste the repository URL (or `file:/path/to/build/updatesite`) into **Work with** and press Enter.
3. Check **QuerySweep Multi-Connection Query**, click Next > Finish, accept the unsigned-content warning, and restart DBeaver.

DBeaver must be installed in a writable location (not a read-only `/Applications` or `Program Files`) for in-app plugin installation to work. For a root-owned Linux install you can install from the command line with the p2 director instead — see below.

### Manual (drop-in JAR)

1. Build the plugin (see above)

2. Copy the JAR to DBeaver's plugins directory:
   ```bash
   cp build/libs/io.github.mlorek.querysweep-1.0.0.jar \
     /Applications/DBeaver.app/Contents/Eclipse/plugins/
   ```

3. Register the bundle in `bundles.info` (one-time setup). This file lists all bundles the OSGi framework loads at startup:
   ```bash
   echo "io.github.mlorek.querysweep,1.0.0,plugins/io.github.mlorek.querysweep-1.0.0.jar,4,false" \
     >> /Applications/DBeaver.app/Contents/Eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info
   ```

4. Clear the OSGi cache (first install only):
   ```bash
   rm -rf /Applications/DBeaver.app/Contents/Eclipse/configuration/org.eclipse.osgi
   ```

5. Restart DBeaver

### Command-line install via p2 director

Useful when DBeaver is in a read-only location. Run from the DBeaver install directory:

```bash
java -jar plugins/org.eclipse.equinox.launcher_*.jar \
  -application org.eclipse.equinox.p2.director \
  -repository file:/path/to/build/updatesite \
  -installIU io.github.mlorek.querysweep.feature.feature.group \
  -destination "$PWD" -profile DefaultProfile -nosplash
```

Replace `-repository ... -installIU` with `-uninstallIU io.github.mlorek.querysweep.feature.feature.group` to remove it.

## Publishing to the Eclipse Marketplace

DBeaver has no marketplace of its own — DBeaver extensions are distributed as p2 update sites, optionally listed on the [Eclipse Marketplace](https://marketplace.eclipse.org) for discoverability:

1. Host `build/updatesite` at a stable public URL (e.g. GitHub Pages).
2. Create an Eclipse Foundation account and add a listing via **Add Content**, providing the update site URL and the feature ID `io.github.mlorek.querysweep.feature`. New listings pass a ~24h moderation queue.
3. In the listing's install instructions, tell users to use **Help > Install New Software** with the p2 URL — DBeaver does not bundle the Eclipse Marketplace Client.

## Usage

1. Open the view: **Window > Show View > Other** and search "QuerySweep", or via the **QuerySweep** menu
2. (Optional) Type in the filter box to narrow the connection list
3. Check the connections you want to query in the connection grid
4. Write SQL in the query editor (use `${connection}` or custom placeholders)
5. Click **Run**

Results appear in the table with rows colored by connection. Click column headers to sort. The status panel shows progress and timing for each connection.

### Placeholders

Click **Placeholders** to define custom variables. For example, adding `acct=MY_ACCOUNT` lets you write `SELECT * FROM ${acct}.schema.table` in your SQL.

Two built-in per-connection placeholders are always available and resolve differently for each connection at run time:

| Placeholder | Resolves to |
|-------------|-------------|
| `${connection}` | The connection name exactly as shown in DBeaver |
| `${connection_sql}` | The connection name uppercased, with every non-alphanumeric character replaced by `_` (handy for building identifiers) |

## Persisted data

| File | Purpose |
|------|---------|
| `~/.query-sweep/dbeaver-history.txt` | Query history (last 100 entries) |
| `~/.query-sweep/dbeaver-placeholders.txt` | Custom placeholder definitions |

## Key classes

- `QuerySweepView` — Main view: connection grid, SQL editor, parallel execution, sortable results, status table, history, placeholders
- `OpenQuerySweepHandler` — Command handler to open the view
- `Activator` — OSGi bundle activator

## License

Apache License 2.0 — see [LICENSE](LICENSE).
