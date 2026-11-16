import java.awt.*;
import java.awt.geom.Path2D;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/**
 * Project 10: FinOps Pricing Simulator (v2 - live data).
 *
 * Every price is fetched live from the Azure Retail Prices API, Spot interruption risk from the
 * AWS Spot Instance Advisor dataset, and the workload demand curve from real Wikimedia traffic
 * (or the user's own usage CSV). Nothing is a fictional constant.
 *
 * Run modes:
 *   java -cp out FinOpsPricingSimulator               -> Swing desktop GUI
 *   java -cp out FinOpsPricingSimulator --web [port]  -> live web dashboard on http://localhost:8080
 *   java -cp out FinOpsPricingSimulator --self-test   -> console report using live data
 */
public class FinOpsPricingSimulator extends JFrame {
    private static int webPort = 8080;
    private static com.sun.net.httpserver.HttpServer webServer;

    private final JComboBox<String> regionBox = new JComboBox<>(PriceService.REGIONS.keySet().toArray(new String[0]));
    private final JComboBox<String> skuBox = new JComboBox<>(PriceService.SKUS.keySet().toArray(new String[0]));
    private final JComboBox<String> currencyBox = new JComboBox<>(PriceService.CURRENCIES);
    private final JComboBox<WorkloadService.Preset> workloadBox = new JComboBox<>(WorkloadService.Preset.values());
    private final JTextField articleField = new JTextField("Cricket", 12);
    private final JSpinner fleetSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 5000, 1));
    private final JSpinner demandSpinner = new JSpinner(new SpinnerNumberModel(1.00, 0.10, 5.00, 0.05));
    private final JSpinner priceSpinner = new JSpinner(new SpinnerNumberModel(1.00, 0.50, 2.00, 0.05));
    private final JComboBox<String> termBox = new JComboBox<>(new String[]{"1-year reservation", "3-year reservation"});
    private final JCheckBox tolerantBox = new JCheckBox("Interruption-tolerant", true);
    private final JTable resultTable = new JTable(), chargebackTable = new JTable(), sensitivityTable = new JTable(), sourceTable = new JTable();
    private final JTextArea recommendationArea = new JTextArea();
    private final ChartPanel demandChart = new ChartPanel();
    private final JLabel statusLabel = new JLabel("Ready");
    private final JButton runButton = new JButton("Fetch live data & simulate");
    private String csvData;
    private JTabbedPane tabs;

    public FinOpsPricingSimulator() {
        setTitle("FinOps Pricing Simulator - live cloud prices");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1180, 760));
        setLocationRelativeTo(null);
        buildInterface();
        runSimulation();
    }

    private void buildInterface() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(12, 14, 10, 14));
        setContentPane(root);

        JLabel title = new JLabel("Project 10: FinOps Pricing Simulator  -  live prices, real workloads");
        title.setFont(new Font("SansSerif", Font.BOLD, 22));
        title.setForeground(new Color(25, 70, 120));

        regionBox.setRenderer(labelRenderer(PriceService.REGIONS));
        skuBox.setRenderer(labelRenderer(PriceService.SKUS));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row1.add(new JLabel("Region:")); row1.add(regionBox);
        row1.add(new JLabel("VM size:")); row1.add(skuBox);
        row1.add(new JLabel("Currency:")); row1.add(currencyBox);
        row1.add(termBox); row1.add(tolerantBox);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        row2.add(new JLabel("Workload:")); row2.add(workloadBox);
        row2.add(new JLabel("Article:")); row2.add(articleField);
        JButton csvButton = new JButton("Load usage CSV...");
        csvButton.addActionListener(e -> loadCsv());
        row2.add(csvButton);
        row2.add(new JLabel("Avg fleet (VMs):")); row2.add(fleetSpinner);
        row2.add(new JLabel("Demand x")); row2.add(demandSpinner);
        row2.add(new JLabel("Price x")); row2.add(priceSpinner);

        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        runButton.addActionListener(e -> runSimulation());
        JButton webButton = new JButton("Open live web dashboard");
        webButton.addActionListener(e -> openWebDashboard());
        row3.add(runButton); row3.add(webButton);

        JPanel controls = new JPanel(new GridLayout(3, 1));
        controls.setBorder(BorderFactory.createTitledBorder("Simulation inputs (prices are fetched live - only your own operational numbers are entered here)"));
        controls.add(row1); controls.add(row2); controls.add(row3);

        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.add(title, BorderLayout.NORTH);
        top.add(controls, BorderLayout.CENTER);
        root.add(top, BorderLayout.NORTH);

        recommendationArea.setEditable(false);
        recommendationArea.setLineWrap(true);
        recommendationArea.setWrapStyleWord(true);
        recommendationArea.setRows(5);
        recommendationArea.setFont(new Font("SansSerif", Font.PLAIN, 13));
        recommendationArea.setBorder(BorderFactory.createTitledBorder("Recommendation"));
        JPanel comparison = new JPanel(new BorderLayout(8, 8));
        comparison.add(new JScrollPane(resultTable), BorderLayout.CENTER);
        comparison.add(new JScrollPane(recommendationArea), BorderLayout.SOUTH);

        tabs = new JTabbedPane();
        tabs.addTab("Cost comparison", comparison);
        tabs.addTab("Real demand curve", demandChart);
        tabs.addTab("Chargeback / showback", withNote(chargebackTable, "Each department runs its own real workload on its share of the fleet and gets its own cheapest strategy."));
        tabs.addTab("Sensitivity", withNote(sensitivityTable, "Lowest-cost strategy and cost when demand and live prices move +/-20%."));
        tabs.addTab("Live price sources", withNote(sourceTable, "Every rate used above, with the API it came from and when it was fetched."));
        root.add(tabs, BorderLayout.CENTER);
        root.add(statusLabel, BorderLayout.PAGE_END);
        sensitivityTable.setRowHeight(40);
    }

    private static ListCellRenderer<Object> labelRenderer(Map<String, String[]> labels) {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                String[] l = labels.get(String.valueOf(value));
                return super.getListCellRendererComponent(list, l == null ? value : l[0], index, sel, focus);
            }
        };
    }

    private static JPanel withNote(JTable table, String note) {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        table.setAutoCreateRowSorter(true);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        JLabel l = new JLabel(note);
        l.setBorder(new EmptyBorder(6, 6, 6, 6));
        p.add(l, BorderLayout.SOUTH);
        return p;
    }

    private void loadCsv() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            csvData = Files.readString(fc.getSelectedFile().toPath());
            workloadBox.setSelectedItem(WorkloadService.Preset.CSV);
            statusLabel.setText("Loaded " + fc.getSelectedFile().getName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not read file: " + ex.getMessage());
        }
    }

    private Engine.Inputs inputs() {
        Engine.Inputs in = new Engine.Inputs();
        in.avgFleet = ((Number) fleetSpinner.getValue()).doubleValue();
        in.demandMultiplier = ((Number) demandSpinner.getValue()).doubleValue();
        in.priceMultiplier = ((Number) priceSpinner.getValue()).doubleValue();
        in.termYears = termBox.getSelectedIndex() == 1 ? 3 : 1;
        in.interruptionTolerant = tolerantBox.isSelected();
        return in;
    }

    private void runSimulation() {
        String region = (String) regionBox.getSelectedItem(), sku = (String) skuBox.getSelectedItem(), cur = (String) currencyBox.getSelectedItem();
        WorkloadService.Preset preset = (WorkloadService.Preset) workloadBox.getSelectedItem();
        String article = articleField.getText();
        Engine.Inputs in = inputs();
        runButton.setEnabled(false);
        statusLabel.setText("Fetching live prices from Azure, Spot risk from AWS and real traffic from Wikimedia...");
        new SwingWorker<Map<String, Object>, Void>() {
            @Override protected Map<String, Object> doInBackground() throws Exception {
                PriceService.PriceSheet p = PriceService.load(region, sku, cur);
                WorkloadService.Workload w = WorkloadService.load(preset, article, csvData);
                return Engine.report(p, w, in, true);
            }
            @Override protected void done() {
                runButton.setEnabled(true);
                try {
                    render(get());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    statusLabel.setText("Error: " + c.getMessage());
                    JOptionPane.showMessageDialog(FinOpsPricingSimulator.this, c.getMessage(), "Live data error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void render(Map<String, Object> r) {
        Map<String, Object> prices = Json.obj(r.get("prices"));
        Map<String, Object> wl = Json.obj(r.get("workload"));
        String sym = Json.str(prices, "symbol");

        String[] cols = {"Strategy", "Total cost", "Compute", "Shared (storage+egress)", "Spot rework", "Daily variability", "Evictions/month", "Effect"};
        List<Object[]> rows = new ArrayList<>();
        for (Object o : Json.arr(r.get("results"))) {
            Map<String, Object> s = Json.obj(o);
            boolean ok = Boolean.TRUE.equals(s.get("available"));
            String label = Json.str(s, "label") + (Boolean.TRUE.equals(s.get("eligible")) ? "" : " (excluded)");
            rows.add(new Object[]{label, ok ? money(sym, s, "total") : "n/a", ok ? money(sym, s, "compute") : "",
                    money(sym, s, "shared"), ok ? money(sym, s, "rework") : "", ok ? money(sym, s, "variability") : "",
                    ok ? String.format("%.1f", Json.num(s, "interruptions")) : "", Json.str(s, "effect")});
        }
        resultTable.setModel(readOnly(rows, cols));
        resultTable.getColumnModel().getColumn(7).setPreferredWidth(420);

        Map<String, Object> rec = Json.obj(r.get("recommendation"));
        StringBuilder t = new StringBuilder(Json.str(rec, "text"));
        t.append("\n\nLive rates (").append(Json.str(prices, "skuLabel")).append(", ").append(Json.str(prices, "regionLabel")).append("): ")
                .append("pay-as-you-go ").append(PriceService.fmt(Json.num(prices, "payg"), Json.str(prices, "currency"))).append("/h, ")
                .append("reserved 1-yr ").append(PriceService.fmt(Json.num(prices, "reserved1y"), Json.str(prices, "currency"))).append("/h, ")
                .append("3-yr ").append(PriceService.fmt(Json.num(prices, "reserved3y"), Json.str(prices, "currency"))).append("/h, ")
                .append("Spot ").append(PriceService.fmt(Json.num(prices, "spot"), Json.str(prices, "currency"))).append("/h. Fetched ")
                .append(Json.str(prices, "fetchedAt")).append(Boolean.TRUE.equals(prices.get("stale")) ? " (OFFLINE - last cached copy)" : "");
        for (Object w : Json.arr(prices.get("warnings"))) t.append("\nNote: ").append(w);
        recommendationArea.setText(t.toString());

        List<Object[]> cb = new ArrayList<>();
        for (Object o : Json.arr(r.get("chargeback"))) {
            Map<String, Object> d = Json.obj(o);
            cb.add(new Object[]{Json.str(d, "department"), Json.str(d, "workload"), Json.str(d, "pattern"),
                    Math.round(Json.num(d, "share") * 100) + "%", String.format("%.1f", Json.num(d, "fleet")),
                    Json.str(d, "strategy"), money(sym, d, "cost"), money(sym, d, "savings")});
        }
        chargebackTable.setModel(readOnly(cb, new String[]{"Department", "Real workload", "Pattern", "Share", "Avg VMs", "Best strategy", "Allocated cost", "Saved vs PAYG"}));

        Map<String, Object> sens = Json.obj(r.get("sensitivity"));
        List<Object> grid = Json.arr(sens.get("grid"));
        String[] pf = {"Price -20%", "Price baseline", "Price +20%"};
        List<Object[]> srows = new ArrayList<>();
        for (int i = 0; i < grid.size(); i++) {
            List<Object> row = Json.arr(grid.get(i));
            Object[] out = new Object[4];
            out[0] = pf[i];
            for (int j = 0; j < row.size(); j++) {
                Map<String, Object> c = Json.obj(row.get(j));
                out[j + 1] = "<html>" + Json.str(c, "strategy") + "<br><b>" + money(sym, c, "total") + "</b></html>";
            }
            srows.add(out);
        }
        sensitivityTable.setModel(readOnly(srows, new String[]{"Live compute price", "Demand -20%", "Demand baseline", "Demand +20%"}));

        List<Object[]> src = new ArrayList<>();
        for (Object o : Json.arr(prices.get("sources"))) {
            Map<String, Object> s = Json.obj(o);
            src.add(new Object[]{Json.str(s, "item"), Json.str(s, "value"), Json.str(s, "unit"), Json.str(s, "source"), Json.str(s, "retrieved")});
        }
        src.add(new Object[]{"Workload demand: " + Json.str(wl, "name"), "30 daily values", "page views/day -> VM-hours", Json.str(wl, "source"), Json.str(wl, "fetchedAt")});
        sourceTable.setModel(readOnly(src, new String[]{"Item", "Value", "Unit", "Source", "Retrieved (UTC)"}));

        demandChart.setData(Json.arr(wl.get("dates")), toArray(wl.get("vmHours")),
                Json.str(wl, "name") + "  -  detected: " + Json.str(wl, "detectedPattern") + " (" + Json.str(wl, "patternReason") + ")");

        statusLabel.setText("Live simulation complete - prices fetched " + Json.str(prices, "fetchedAt") + "; traffic up to " + lastDate(wl));
    }

    private static String lastDate(Map<String, Object> wl) {
        List<Object> d = Json.arr(wl.get("dates"));
        return d.isEmpty() ? "" : d.get(d.size() - 1).toString();
    }

    private static double[] toArray(Object o) {
        if (o instanceof double[]) return (double[]) o;
        List<Object> l = Json.arr(o);
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = ((Number) l.get(i)).doubleValue();
        return a;
    }

    private static String money(String sym, Map<String, Object> m, String k) {
        double v = Json.num(m, k);
        return Double.isNaN(v) ? "n/a" : sym + String.format("%,.2f", v);
    }

    private static DefaultTableModel readOnly(List<Object[]> rows, String[] cols) {
        return new DefaultTableModel(rows.toArray(new Object[0][]), cols) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    private void openWebDashboard() {
        try {
            if (webServer == null) webServer = WebServer.start(webPort, webDir());
            URI uri = URI.create("http://localhost:" + webPort + "/");
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(uri);
            statusLabel.setText("Web dashboard running at " + uri);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not start web dashboard: " + ex.getMessage());
        }
    }

    static Path webDir() {
        String home = System.getProperty("finops.home");
        if (home != null) return Paths.get(home, "web").toAbsolutePath().normalize();
        Path p = Paths.get("web").toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) p = Paths.get("..", "web").toAbsolutePath().normalize();
        return p;
    }

    /** Simple line chart of the real daily demand (VM-hours). */
    static final class ChartPanel extends JPanel {
        private List<Object> dates = new ArrayList<>();
        private double[] values = new double[0];
        private String caption = "";

        void setData(List<Object> dates, double[] values, String caption) {
            this.dates = dates; this.values = values; this.caption = caption;
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), left = 70, right = 20, top = 40, bottom = 50;
            g.setColor(Color.DARK_GRAY);
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.drawString("Daily compute demand (VM-hours) from real traffic: " + caption, left, 22);
            if (values.length < 2) return;
            double max = Arrays.stream(values).max().orElse(1) * 1.1;
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            for (int k = 0; k <= 4; k++) {
                int y = top + (h - top - bottom) * k / 4;
                g.setColor(new Color(230, 230, 230));
                g.drawLine(left, y, w - right, y);
                g.setColor(Color.GRAY);
                g.drawString(String.format("%,.0f", max * (4 - k) / 4), 8, y + 4);
            }
            Path2D path = new Path2D.Double();
            for (int i = 0; i < values.length; i++) {
                double x = left + (w - left - right) * i / (double) (values.length - 1);
                double y = top + (h - top - bottom) * (1 - values[i] / max);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                if (i % 5 == 0 || i == values.length - 1) {
                    g.setColor(Color.GRAY);
                    String d = String.valueOf(dates.get(i));
                    g.drawString(d.length() >= 10 ? d.substring(5) : d, (int) x - 14, h - bottom + 18);
                }
            }
            g.setColor(new Color(35, 100, 190));
            g.setStroke(new BasicStroke(2.4f));
            g.draw(path);
        }
    }

    // ------------------------------------------------------------------ entry point

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equalsIgnoreCase("--self-test")) { selfTest(); return; }
        if (args.length > 0 && args[0].equalsIgnoreCase("--web")) {
            if (args.length > 1) webPort = Integer.parseInt(args[1]);
            else if (System.getenv("PORT") != null) webPort = Integer.parseInt(System.getenv("PORT")); // cloud hosts set PORT
            webServer = WebServer.start(webPort, webDir());
            System.out.println("FinOps Pricing Simulator web dashboard: http://localhost:" + webPort + "/  (Ctrl+C to stop)");
            return;
        }
        SwingUtilities.invokeLater(() -> new FinOpsPricingSimulator().setVisible(true));
    }

    private static void selfTest() throws Exception {
        System.out.println("FinOps Pricing Simulator self-test (live data)");
        PriceService.PriceSheet p = PriceService.load("eastus", "Standard_D2s_v5", "USD");
        System.out.printf("Azure %s %s: PAYG $%.4f/h, RI-1y $%.4f/h, RI-3y $%.4f/h, Spot $%.4f/h, interruption %s%n",
                p.sku, p.region, p.payg, p.reserved1y, p.reserved3y, p.spot, p.spotBand);
        for (WorkloadService.Preset preset : new WorkloadService.Preset[]{WorkloadService.Preset.STEADY, WorkloadService.Preset.SPIKY, WorkloadService.Preset.SEASONAL}) {
            WorkloadService.Workload w = WorkloadService.load(preset, null, null);
            Engine.Inputs in = new Engine.Inputs();
            List<Engine.Result> rs = Engine.simulateAll(p, Engine.demandHours(w, in), in);
            Engine.Result best = Engine.best(rs), g = Engine.bestGuaranteed(rs);
            System.out.printf("%-9s %-34s detected=%-8s -> %-24s $%,9.2f | guaranteed: %-18s $%,9.2f (%s) | PAYG $%,.2f%n", preset.label,
                    w.name.length() > 34 ? w.name.substring(0, 34) : w.name, w.detectedPattern, best.strategy.label, best.total,
                    g.strategy.label, g.total, rs.get(1).effect, rs.get(0).total);
        }
    }
}
