package io.github.mlorek.querysweep;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.exec.*;
import org.jkiss.dbeaver.model.impl.local.LocalResultSet;
import org.jkiss.dbeaver.model.impl.local.LocalStatement;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSInstance;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.controls.resultset.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class QuerySweepView extends ViewPart {

    public static final String VIEW_ID = "io.github.mlorek.querysweep.view";

    private static final Color[] ROW_COLORS = new Color[20];

    private Text queryText;
    private Table statusTable;
    private Label progressLabel;
    private Button runButton;
    private Button cancelButton;
    private Composite connectionGrid;
    private Text connectionFilter;
    private final List<Button> connectionCheckboxes = new ArrayList<>();

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger completed = new AtomicInteger(0);
    private int total;

    private ResultSetViewer resultSetViewer;
    private final QuerySweepDataContainer dataContainer = new QuerySweepDataContainer();
    private volatile DBCExecutionContext activeExecutionContext;
    private final Map<String, Integer> connectionColorMap = new LinkedHashMap<>();

    private static final Path HISTORY_PATH = Path.of(System.getProperty("user.home"), ".query-sweep", "dbeaver-history.txt");
    private static final Path PLACEHOLDERS_PATH = Path.of(System.getProperty("user.home"), ".query-sweep", "dbeaver-placeholders.txt");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final List<String> queryHistory = new ArrayList<>();
    private final Map<String, String> placeholders = new LinkedHashMap<>();

    @Override
    public void createPartControl(Composite parent) {
        initColors(parent.getDisplay());

        SashForm mainSash = new SashForm(parent, SWT.VERTICAL);

        createTopPanel(mainSash);

        SashForm bottomSash = new SashForm(mainSash, SWT.HORIZONTAL);
        createResultSetViewer(bottomSash);
        createStatusTable(bottomSash);
        bottomSash.setWeights(new int[]{75, 25});

        progressLabel = new Label(mainSash, SWT.NONE);
        progressLabel.setText("Progress: 0/0");

        mainSash.setWeights(new int[]{30, 65, 5});
    }

    private void initColors(Display display) {
        int[][] rgb = {
                {255, 230, 230}, {230, 255, 230}, {230, 230, 255}, {255, 255, 210},
                {255, 220, 255}, {220, 255, 255}, {255, 235, 200}, {200, 255, 220},
                {235, 220, 255}, {220, 245, 200}, {255, 210, 230}, {210, 240, 255},
                {245, 255, 200}, {255, 225, 240}, {225, 255, 245}, {240, 225, 200},
                {200, 230, 255}, {255, 240, 220}, {220, 200, 240}, {240, 255, 230}
        };
        for (int i = 0; i < ROW_COLORS.length; i++) {
            ROW_COLORS[i] = new Color(display, rgb[i][0], rgb[i][1], rgb[i][2]);
        }
    }

    private void createTopPanel(Composite parent) {
        Composite topPanel = new Composite(parent, SWT.NONE);
        topPanel.setLayout(new GridLayout(2, false));

        SashForm topSash = new SashForm(topPanel, SWT.HORIZONTAL);
        topSash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));

        createConnectionSelector(topSash);
        createQueryPanel(topSash);

        topSash.setWeights(new int[]{30, 70});

        Composite buttonPanel = new Composite(topPanel, SWT.NONE);
        buttonPanel.setLayout(new GridLayout(2, true));
        buttonPanel.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false, 2, 1));

        runButton = new Button(buttonPanel, SWT.PUSH);
        runButton.setText("Run");
        runButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        runButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                executeQuery();
            }
        });

        cancelButton = new Button(buttonPanel, SWT.PUSH);
        cancelButton.setText("Cancel");
        cancelButton.setEnabled(false);
        cancelButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cancelButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                cancelled.set(true);
                cancelButton.setEnabled(false);
            }
        });
    }

    private void createConnectionSelector(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Connections");
        group.setLayout(new GridLayout(1, false));

        connectionFilter = new Text(group, SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
        connectionFilter.setMessage("Filter by connection or driver name");
        connectionFilter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        ScrolledComposite scroll = new ScrolledComposite(group, SWT.V_SCROLL | SWT.BORDER);
        scroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);

        connectionGrid = new Composite(scroll, SWT.NONE);
        RowLayout rowLayout = new RowLayout(SWT.HORIZONTAL);
        rowLayout.wrap = true;
        rowLayout.spacing = 6;
        rowLayout.marginWidth = 4;
        rowLayout.marginHeight = 4;
        connectionGrid.setLayout(rowLayout);
        scroll.setContent(connectionGrid);

        loadConnections();

        scroll.setMinSize(connectionGrid.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        scroll.addListener(SWT.Resize, e -> {
            int width = scroll.getClientArea().width;
            scroll.setMinSize(connectionGrid.computeSize(width, SWT.DEFAULT));
        });

        connectionFilter.addListener(SWT.Modify, e -> {
            loadConnections();
            int width = scroll.getClientArea().width;
            scroll.setMinSize(connectionGrid.computeSize(width, SWT.DEFAULT));
        });

        Composite btnPanel = new Composite(group, SWT.NONE);
        btnPanel.setLayout(new RowLayout(SWT.HORIZONTAL));
        btnPanel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button selectAll = new Button(btnPanel, SWT.PUSH);
        selectAll.setText("All");
        selectAll.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                connectionCheckboxes.forEach(cb -> cb.setSelection(true));
            }
        });

        Button deselectAll = new Button(btnPanel, SWT.PUSH);
        deselectAll.setText("None");
        deselectAll.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                connectionCheckboxes.forEach(cb -> cb.setSelection(false));
            }
        });

        Button refreshButton = new Button(btnPanel, SWT.PUSH);
        refreshButton.setText("Refresh");
        refreshButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                loadConnections();
                int width = scroll.getClientArea().width;
                scroll.setMinSize(connectionGrid.computeSize(width, SWT.DEFAULT));
            }
        });
    }

    private void createQueryPanel(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("SQL Query");
        group.setLayout(new GridLayout(1, false));

        queryText = new Text(group, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        queryText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        queryText.setText("SELECT '${connection}' AS connection, current_timestamp AS ts");

        Composite queryBtnPanel = new Composite(group, SWT.NONE);
        queryBtnPanel.setLayout(new RowLayout(SWT.HORIZONTAL));
        queryBtnPanel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Button historyButton = new Button(queryBtnPanel, SWT.PUSH);
        historyButton.setText("History");
        historyButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                showHistory();
            }
        });

        Button placeholdersButton = new Button(queryBtnPanel, SWT.PUSH);
        placeholdersButton.setText("Placeholders");
        placeholdersButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                showPlaceholders();
            }
        });

        loadHistory();
        loadPlaceholders();
    }

    private void createResultSetViewer(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Results");
        group.setLayout(new GridLayout(1, true));

        IResultSetContainer container = new IResultSetContainer() {
            @Override
            public DBPProject getProject() {
                var projects = DBWorkbench.getPlatform().getWorkspace().getProjects();
                return projects.isEmpty() ? null : projects.get(0);
            }

            @Override
            public DBSDataContainer getDataContainer() {
                return dataContainer;
            }

            @Override
            public boolean isReadyToRun() {
                return activeExecutionContext != null;
            }

            @Override
            public void openNewContainer(DBRProgressMonitor monitor, DBSDataContainer dc, DBDDataFilter filter) {
            }

            @Override
            public IResultSetDecorator createResultSetDecorator() {
                return new ResultSetDecoratorBase() {
                    @Override
                    public long getDecoratorFeatures() {
                        return IResultSetDecorator.FEATURE_STATUS_BAR | IResultSetDecorator.FEATURE_PANELS;
                    }

                    @Override
                    public String getEmptyDataMessage() {
                        return "Run a query to see results";
                    }

                    @Override
                    public String getEmptyDataDescription() {
                        return "";
                    }
                };
            }

            @Override
            public DBCExecutionContext getExecutionContext() {
                return activeExecutionContext;
            }

            @Override
            public IResultSetController getResultSetController() {
                return resultSetViewer;
            }
        };

        resultSetViewer = new ResultSetViewer(group, getSite(), container);
        resultSetViewer.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    }

    private void createStatusTable(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Connection Status");
        group.setLayout(new GridLayout(1, false));

        statusTable = new Table(group, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        statusTable.setHeaderVisible(true);
        statusTable.setLinesVisible(true);
        statusTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        TableColumn nameCol = new TableColumn(statusTable, SWT.NONE);
        nameCol.setText("Connection");
        nameCol.setWidth(180);

        TableColumn statusCol = new TableColumn(statusTable, SWT.NONE);
        statusCol.setText("Status");
        statusCol.setWidth(120);
    }

    private void loadConnections() {
        for (Control child : connectionGrid.getChildren()) {
            child.dispose();
        }
        connectionCheckboxes.clear();

        String filter = connectionFilter != null ? connectionFilter.getText().trim().toLowerCase() : "";
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            project.getDataSourceRegistry().getDataSources().stream()
                    .filter(ds -> filter.isEmpty()
                            || ds.getName().toLowerCase().contains(filter)
                            || ds.getDriver().getName().toLowerCase().contains(filter))
                    .forEach(ds -> {
                        Button cb = new Button(connectionGrid, SWT.CHECK);
                        cb.setText(ds.getName());
                        cb.setSelection(true);
                        cb.setData(new ConnectionInfo(ds.getName(), ds.getId()));
                        connectionCheckboxes.add(cb);
                    });
        }
        connectionGrid.layout(true);
    }

    private void executeQuery() {
        List<ConnectionInfo> checked = connectionCheckboxes.stream()
                .filter(Button::getSelection)
                .map(cb -> (ConnectionInfo) cb.getData())
                .toList();

        if (checked.isEmpty()) {
            MessageBox msg = new MessageBox(getSite().getShell(), SWT.ICON_WARNING | SWT.OK);
            msg.setMessage("No connections selected.");
            msg.open();
            return;
        }

        String sql = queryText.getText().trim();
        if (sql.isEmpty()) {
            return;
        }

        addToHistory(sql);
        cancelled.set(false);
        completed.set(0);
        total = checked.size();
        runButton.setEnabled(false);
        cancelButton.setEnabled(true);
        statusTable.removeAll();
        progressLabel.setText("Progress: 0/0");
        activeExecutionContext = null;
        connectionColorMap.clear();

        Display display = getSite().getShell().getDisplay();
        AtomicBoolean headerSet = new AtomicBoolean(false);
        AtomicInteger colorIdx = new AtomicInteger(0);

        List<String> columns = new ArrayList<>();
        List<Object[]> allRows = new ArrayList<>();
        Object lock = new Object();

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        for (ConnectionInfo info : checked) {
            int myColorIdx = colorIdx.getAndIncrement() % ROW_COLORS.length;
            connectionColorMap.put(info.name(), myColorIdx);

            display.asyncExec(() -> {
                TableItem statusItem = new TableItem(statusTable, SWT.NONE);
                statusItem.setText(0, info.name());
                statusItem.setText(1, "RUNNING");
                statusItem.setBackground(0, ROW_COLORS[myColorIdx]);
                statusItem.setBackground(1, new Color(display, 255, 255, 200));
                statusItem.setForeground(display.getSystemColor(SWT.COLOR_BLACK));
            });

            executor.submit(() -> {
                if (cancelled.get()) return;

                String rewrittenSql = rewriteQuery(info.name(), sql);
                long start = System.currentTimeMillis();
                try {
                    DBPDataSourceContainer dsContainer = findDataSource(info.id());
                    if (dsContainer == null) {
                        throw new Exception("Connection not found: " + info.name());
                    }

                    VoidProgressMonitor monitor = new VoidProgressMonitor();

                    if (!dsContainer.isConnected()) {
                        dsContainer.connect(monitor, true, true);
                    }

                    DBPDataSource dataSource = dsContainer.getDataSource();
                    if (dataSource == null) {
                        throw new Exception("Could not connect to: " + info.name());
                    }

                    DBSInstance instance = dataSource.getDefaultInstance();
                    if (activeExecutionContext == null) {
                        activeExecutionContext = instance.getDefaultContext(monitor, false);
                    }
                    DBCExecutionContext context = instance.openIsolatedContext(monitor, "QuerySweep", null);

                    List<Object[]> rows = new ArrayList<>();
                    String[] header;

                    try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.USER, "QuerySweep query")) {
                        try (DBCStatement stmt = session.prepareStatement(DBCStatementType.QUERY, rewrittenSql, false, false, false)) {
                            if (stmt.executeStatement()) {
                                try (DBCResultSet rs = stmt.openResultSet()) {
                                    DBCResultSetMetaData meta = rs.getMeta();
                                    int colCount = meta.getAttributes().size();
                                    header = new String[colCount + 1];
                                    header[0] = "connection";
                                    for (int i = 0; i < colCount; i++) {
                                        header[i + 1] = meta.getAttributes().get(i).getLabel();
                                    }

                                    while (rs.nextRow()) {
                                        Object[] row = new Object[colCount + 1];
                                        row[0] = info.name();
                                        for (int i = 0; i < colCount; i++) {
                                            Object val = rs.getAttributeValue(i);
                                            row[i + 1] = val != null ? val.toString() : "";
                                        }
                                        rows.add(row);
                                    }
                                }
                            } else {
                                header = new String[]{"connection", "result"};
                                rows.add(new Object[]{info.name(), "Statement executed (no result set)"});
                            }
                        }
                    } finally {
                        context.close();
                    }

                    long elapsed = System.currentTimeMillis() - start;

                    synchronized (lock) {
                        if (headerSet.compareAndSet(false, true)) {
                            columns.clear();
                            for (String col : header) {
                                columns.add(col);
                            }
                        }
                        allRows.addAll(rows);
                    }

                    display.asyncExec(() -> updateStatus(info.name(), String.format("OK (%d) %.1fs", rows.size(), elapsed / 1000.0)));

                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - start;
                    display.asyncExec(() -> updateStatus(info.name(), String.format("FAIL %.1fs", elapsed / 1000.0)));
                }
            });
        }

        executor.shutdown();
        new Thread(() -> {
            try {
                executor.awaitTermination(5, TimeUnit.MINUTES);
            } catch (InterruptedException ignored) {
            }

            display.asyncExec(() -> {
                runButton.setEnabled(true);
                cancelButton.setEnabled(false);
                progressLabel.setText(progressLabel.getText() + "  — DONE");
                dataContainer.setData(columns, allRows);
                resultSetViewer.refreshData(QuerySweepView.this::applyRowColors);
            });
        }).start();
    }

    private void applyRowColors() {
        ResultSetModel model = resultSetViewer.getModel();
        List<ResultSetRow> rows = model.getAllRows();
        for (ResultSetRow row : rows) {
            if (row.values.length == 0) continue;
            Object val = row.values[0];
            String connName = val != null ? val.toString() : "";
            Integer idx = connectionColorMap.get(connName);
            if (idx != null) {
                if (row.colorInfo == null) {
                    row.colorInfo = new ResultSetRow.ColorInfo();
                }
                row.colorInfo.rowBackground = ROW_COLORS[idx];
            }
        }
        resultSetViewer.getControl().redraw();
    }

    private String rewriteQuery(String connectionName, String query) {
        String sqlName = connectionName.replaceAll("[^A-Za-z0-9]", "_").toUpperCase();
        String sql = query.replace("${connection}", connectionName)
                .replace("${connection_sql}", sqlName);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            sql = sql.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return sql;
    }

    private DBPDataSourceContainer findDataSource(String connectionId) {
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            DBPDataSourceContainer ds = project.getDataSourceRegistry().getDataSource(connectionId);
            if (ds != null) {
                return ds;
            }
        }
        return null;
    }

    private void updateStatus(String name, String status) {
        int done = completed.incrementAndGet();
        progressLabel.setText(String.format("Progress: %d/%d  [last: %s %s]", done, total, name, status));
        Display display = statusTable.getDisplay();
        for (int i = 0; i < statusTable.getItemCount(); i++) {
            if (name.equals(statusTable.getItem(i).getText(0))) {
                statusTable.getItem(i).setText(1, status);
                if (status.startsWith("OK")) {
                    statusTable.getItem(i).setBackground(1, new Color(display, 200, 255, 200));
                } else if (status.startsWith("FAIL")) {
                    statusTable.getItem(i).setBackground(1, new Color(display, 255, 200, 200));
                }
                break;
            }
        }
    }

    private void addToHistory(String sql) {
        String entry = FORMATTER.format(LocalDateTime.now()) + "\t" + sql.replace("\n", " ").replace("\r", "");
        queryHistory.addFirst(entry);
        if (queryHistory.size() > 100) {
            queryHistory.removeLast();
        }
        saveHistory();
    }

    private void loadHistory() {
        if (!Files.exists(HISTORY_PATH)) return;
        try {
            List<String> lines = Files.readAllLines(HISTORY_PATH);
            queryHistory.clear();
            queryHistory.addAll(lines);
        } catch (IOException ignored) {
        }
    }

    private void saveHistory() {
        try {
            Files.createDirectories(HISTORY_PATH.getParent());
            Files.write(HISTORY_PATH, queryHistory);
        } catch (IOException ignored) {
        }
    }

    private void showHistory() {
        if (queryHistory.isEmpty()) {
            MessageBox msg = new MessageBox(getSite().getShell(), SWT.ICON_INFORMATION | SWT.OK);
            msg.setMessage("No query history yet.");
            msg.open();
            return;
        }

        Shell dialog = new Shell(getSite().getShell(), SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
        dialog.setText("Query History");
        dialog.setSize(800, 500);
        dialog.setLayout(new GridLayout(1, false));

        Table historyTable = new Table(dialog, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        historyTable.setHeaderVisible(true);
        historyTable.setLinesVisible(true);
        historyTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        historyTable.setFont(dialog.getDisplay().getSystemFont());

        TableColumn dateCol = new TableColumn(historyTable, SWT.NONE);
        dateCol.setText("Date");
        dateCol.setWidth(150);

        TableColumn queryCol = new TableColumn(historyTable, SWT.NONE);
        queryCol.setText("Query");
        queryCol.setWidth(600);

        for (String entry : queryHistory) {
            TableItem item = new TableItem(historyTable, SWT.NONE);
            int tab = entry.indexOf('\t');
            if (tab > 0) {
                item.setText(0, entry.substring(0, tab));
                item.setText(1, entry.substring(tab + 1));
            } else {
                item.setText(0, "");
                item.setText(1, entry);
            }
        }

        historyTable.addListener(SWT.MouseDoubleClick, e -> {
            int idx = historyTable.getSelectionIndex();
            if (idx >= 0) {
                String query = historyTable.getItem(idx).getText(1);
                queryText.setText(query);
                dialog.close();
            }
        });

        Composite btnPanel = new Composite(dialog, SWT.NONE);
        btnPanel.setLayout(new RowLayout(SWT.HORIZONTAL));
        btnPanel.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));

        Button selectBtn = new Button(btnPanel, SWT.PUSH);
        selectBtn.setText("Select");
        selectBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                int idx = historyTable.getSelectionIndex();
                if (idx >= 0) {
                    queryText.setText(historyTable.getItem(idx).getText(1));
                    dialog.close();
                }
            }
        });

        Button clearBtn = new Button(btnPanel, SWT.PUSH);
        clearBtn.setText("Clear History");
        clearBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                queryHistory.clear();
                saveHistory();
                dialog.close();
            }
        });

        Button closeBtn = new Button(btnPanel, SWT.PUSH);
        closeBtn.setText("Close");
        closeBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                dialog.close();
            }
        });

        dialog.open();
    }

    private void loadPlaceholders() {
        placeholders.clear();
        if (!Files.exists(PLACEHOLDERS_PATH)) return;
        try {
            for (String line : Files.readAllLines(PLACEHOLDERS_PATH)) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    placeholders.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void savePlaceholders() {
        try {
            Files.createDirectories(PLACEHOLDERS_PATH.getParent());
            List<String> lines = placeholders.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .toList();
            Files.write(PLACEHOLDERS_PATH, lines);
        } catch (IOException ignored) {
        }
    }

    private void showPlaceholders() {
        Shell dialog = new Shell(getSite().getShell(), SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
        dialog.setText("Placeholders");
        dialog.setSize(600, 400);
        dialog.setLayout(new GridLayout(1, false));

        Label info = new Label(dialog, SWT.WRAP);
        info.setText("Define placeholders as name=value. Use as ${name} in SQL. Built-in per-connection placeholders: ${connection} (the connection name) and ${connection_sql} (uppercased, non-alphanumerics replaced with _).");
        info.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Table table = new Table(dialog, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        TableColumn nameCol = new TableColumn(table, SWT.NONE);
        nameCol.setText("Name");
        nameCol.setWidth(200);

        TableColumn valueCol = new TableColumn(table, SWT.NONE);
        valueCol.setText("Value");
        valueCol.setWidth(350);

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(0, entry.getKey());
            item.setText(1, entry.getValue());
        }

        Composite btnPanel = new Composite(dialog, SWT.NONE);
        btnPanel.setLayout(new RowLayout(SWT.HORIZONTAL));
        btnPanel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button addBtn = new Button(btnPanel, SWT.PUSH);
        addBtn.setText("Add");
        addBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Shell addDialog = new Shell(dialog, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
                addDialog.setText("Add Placeholder");
                addDialog.setLayout(new GridLayout(2, false));
                addDialog.setSize(400, 150);

                new Label(addDialog, SWT.NONE).setText("Name:");
                Text nameText = new Text(addDialog, SWT.BORDER);
                nameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

                new Label(addDialog, SWT.NONE).setText("Value:");
                Text valueText = new Text(addDialog, SWT.BORDER);
                valueText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

                Button okBtn = new Button(addDialog, SWT.PUSH);
                okBtn.setText("OK");
                okBtn.addSelectionListener(new SelectionAdapter() {
                    @Override
                    public void widgetSelected(SelectionEvent ev) {
                        String name = nameText.getText().trim();
                        String value = valueText.getText().trim();
                        if (!name.isEmpty()) {
                            TableItem item = new TableItem(table, SWT.NONE);
                            item.setText(0, name);
                            item.setText(1, value);
                        }
                        addDialog.close();
                    }
                });

                addDialog.open();
            }
        });

        Button removeBtn = new Button(btnPanel, SWT.PUSH);
        removeBtn.setText("Remove");
        removeBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                int idx = table.getSelectionIndex();
                if (idx >= 0) {
                    table.remove(idx);
                }
            }
        });

        Button saveBtn = new Button(btnPanel, SWT.PUSH);
        saveBtn.setText("Save & Close");
        saveBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                placeholders.clear();
                for (int i = 0; i < table.getItemCount(); i++) {
                    String key = table.getItem(i).getText(0).trim();
                    String val = table.getItem(i).getText(1).trim();
                    if (!key.isEmpty()) {
                        placeholders.put(key, val);
                    }
                }
                savePlaceholders();
                dialog.close();
            }
        });

        Button cancelBtn = new Button(btnPanel, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                dialog.close();
            }
        });

        dialog.open();
    }

    @Override
    public void setFocus() {
        queryText.setFocus();
    }

    record ConnectionInfo(String name, String id) {
    }

    private class QuerySweepDataContainer implements DBSDataContainer {

        private List<String> columns = new ArrayList<>();
        private List<Object[]> rows = new ArrayList<>();

        void setData(List<String> columns, List<Object[]> rows) {
            this.columns = columns;
            this.rows = rows;
        }

        List<Object[]> getRows() {
            return rows;
        }

        @Override
        public DBCStatistics readData(DBCExecutionSource source, DBCSession session, DBDDataReceiver dataReceiver, DBDDataFilter dataFilter, long firstRow, long maxRows, long flags, int fetchSize) throws org.jkiss.dbeaver.DBException {
            DBCStatistics stats = new DBCStatistics();
            if (columns.isEmpty()) return stats;

            LocalStatement localStmt = new LocalStatement(session, "QuerySweep");
            LocalResultSet<LocalStatement> localRs = new LocalResultSet<>(session, localStmt);

            for (String col : columns) {
                localRs.addColumn(col, DBPDataKind.STRING);
            }
            for (Object[] row : rows) {
                localRs.addRow(row);
            }

            dataReceiver.fetchStart(session, localRs, firstRow, maxRows);
            for (int i = 0; i < rows.size(); i++) {
                localRs.nextRow();
                dataReceiver.fetchRow(session, localRs);
            }
            dataReceiver.fetchEnd(session, localRs);

            stats.setRowsFetched(rows.size());
            return stats;
        }

        @Override
        public long countData(DBCExecutionSource source, DBCSession session, DBDDataFilter dataFilter, long flags) {
            return rows.size();
        }

        @Override
        public String[] getSupportedFeatures() {
            return new String[]{FEATURE_DATA_SELECT};
        }

        @Override
        public DBPDataSource getDataSource() {
            return activeExecutionContext != null ? activeExecutionContext.getDataSource() : null;
        }

        @Override
        public String getName() {
            return "QuerySweep Results";
        }

        @Override
        public String getDescription() {
            return "QuerySweep multi-connection query results";
        }

        @Override
        public DBSObject getParentObject() {
            return null;
        }

        @Override
        public boolean isPersisted() {
            return false;
        }
    }
}
