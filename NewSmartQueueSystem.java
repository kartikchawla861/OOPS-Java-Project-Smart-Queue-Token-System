import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Smart Queue & Token Management System  -  College Administration Edition (version 2)
 * -------------------------------------------------------------------------------------
 * A single-file Java Swing application (standard Java only, no external libraries).
 *
 * Pages (chosen from the navy sidebar):
 *   Dashboard      - big summary cards, next students in line, quick actions
 *   Manage Queue   - generate tokens, Call Next, Mark Completed, live queue table
 *   Students       - every record, with search + department + status filters
 *   Token Display  - a large screen for the waiting hall (current token + next tokens)
 *   Reports        - department-wise and status-wise totals with bar charts
 *
 * Token life cycle:  WAITING  ->  SERVING  ->  COMPLETED
 *  (A completed token can never be called again.)
 *
 * Data is saved to queue_records.csv (same format as version 1, so old files still work).
 *
 * File layout (top to bottom):
 *  1. Constants and fields
 *  2. main() and the constructor
 *  3. Building the interface (sidebar, top bar, pages)
 *  4. Actions (generate, call next, complete, refresh)
 *  5. Updating every screen from the data
 *  6. Small query helpers and dialogs
 *  7. CSV saving / loading
 *  8. Helper classes (data classes, icons, buttons, cards, charts, table views)
 */
public class NewSmartQueueSystem extends JFrame {

    // =====================================================================
    // 1. CONSTANTS AND FIELDS
    // =====================================================================

    /** The CSV file is created in the folder where you run the program. */
    private static final Path CSV_PATH = Paths.get("queue_records.csv");

    /** First line of the CSV file (unchanged from version 1). */
    private static final String CSV_HEADER = "Token,Name,Department,Status,Created";

    /** The three departments students can choose from. */
    private static final String[] DEPARTMENTS = {"Accounts", "Examination Cell", "Administration"};

    /** Longest student name we accept. */
    private static final int MAX_NAME_LENGTH = 60;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy   HH:mm:ss", Locale.ENGLISH);

    // ----- Page names (also used as the page titles) -----
    private static final String PAGE_DASHBOARD = "Dashboard";
    private static final String PAGE_QUEUE = "Manage Queue";
    private static final String PAGE_STUDENTS = "Students";
    private static final String PAGE_DISPLAY = "Token Display";
    private static final String PAGE_REPORTS = "Reports";

    private static final String ALL_DEPARTMENTS = "All Departments";
    private static final String ALL_STATUSES = "All Statuses";

    // ----- Colours -----
    private static final Color COLOR_SIDEBAR = new Color(15, 23, 70);
    private static final Color COLOR_SIDEBAR_HOVER = new Color(30, 41, 99);
    private static final Color COLOR_SIDEBAR_ACTIVE = new Color(45, 62, 150);
    private static final Color COLOR_NAVY = new Color(30, 41, 99);
    private static final Color COLOR_BLUE = new Color(37, 99, 235);
    private static final Color COLOR_GREEN = new Color(22, 163, 74);
    private static final Color COLOR_ORANGE = new Color(234, 138, 12);
    private static final Color COLOR_GREY = new Color(100, 116, 139);
    private static final Color COLOR_BACKGROUND = new Color(241, 245, 249);
    private static final Color COLOR_TEXT = new Color(30, 41, 59);
    private static final Color COLOR_BORDER = new Color(226, 232, 240);

    // ----- Fonts (used everywhere so the whole app looks consistent) -----
    private static final String FONT_NAME = "Segoe UI";
    private static final Font FONT_TEXT = new Font(FONT_NAME, Font.PLAIN, 15);
    private static final Font FONT_BOLD = new Font(FONT_NAME, Font.BOLD, 15);
    private static final Font FONT_SMALL = new Font(FONT_NAME, Font.PLAIN, 13);

    // ----- Data -----
    /** Every record in the system, always in token order (T001 first). */
    private final List<QueueEntry> entries = new ArrayList<>();

    /** How many bad CSV lines were skipped during the most recent load. */
    private int skippedLines = 0;

    // ----- Navigation -----
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel pageHolder = new JPanel(cardLayout);
    private final List<NavButton> navButtons = new ArrayList<>();
    private JLabel pageTitleLabel;
    private JLabel clockLabel;
    private JLabel statusBarLabel;

    // ----- Dashboard components -----
    private StatCard totalCard;
    private StatCard waitingCard;
    private StatCard servingCard;
    private StatCard completedCard;
    private JPanel nextListPanel;
    private BarChartPanel waitingByDeptChart;

    // ----- Manage Queue components -----
    private JTextField nameField;
    private JComboBox<String> departmentBox;
    private JLabel servingBannerLabel;
    private RecordsView queueView;

    // ----- Students, Display and Reports components -----
    private RecordsView studentsView;
    private TokenDisplayPanel tokenDisplay;
    private DefaultTableModel deptReportModel;
    private DefaultTableModel statusReportModel;
    private BarChartPanel deptTotalsChart;
    private BarChartPanel statusChart;

    // =====================================================================
    // 2. MAIN METHOD AND CONSTRUCTOR
    // =====================================================================

    public static void main(String[] args) {
        // Swing windows must be created on the "Event Dispatch Thread".
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ex) {
                // If this fails Java just uses its default look. Nothing to worry about.
            }
            NewSmartQueueSystem app = new NewSmartQueueSystem();
            app.setVisible(true);
        });
    }

    public NewSmartQueueSystem() {
        super("Smart Queue & Token Management System - College Office");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // A sensible size for when the user un-maximizes, then start maximized (full screen area).
        setSize(1280, 780);
        setMinimumSize(new Dimension(1000, 640));
        setLocationRelativeTo(null);
        setExtendedState(JFrame.MAXIMIZED_BOTH);

        buildUserInterface();

        // Load previously saved records (if the CSV file exists).
        try {
            entries.addAll(readRecordsFromFile());
        } catch (IOException ex) {
            showError("Could not read " + CSV_PATH.toAbsolutePath() + "\n\n" + ex.getMessage()
                    + "\n\nThe app will start with an empty queue.");
        }

        refreshAll();
        showPage(PAGE_DASHBOARD);

        if (skippedLines > 0) {
            setStatus("Loaded " + entries.size() + " records. " + skippedLines
                    + " invalid line(s) in the CSV file were skipped.");
        } else {
            setStatus("Loaded " + entries.size() + " record(s) from " + CSV_PATH.getFileName() + ".");
        }
    }

    // =====================================================================
    // 3. BUILDING THE INTERFACE
    // =====================================================================

    /** Puts the sidebar on the left and the top bar / pages / status bar on the right. */
    private void buildUserInterface() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(COLOR_BACKGROUND);

        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(COLOR_BACKGROUND);
        right.add(createTopBar(), BorderLayout.NORTH);

        pageHolder.setOpaque(false);
        pageHolder.setBorder(BorderFactory.createEmptyBorder(20, 24, 12, 24));
        pageHolder.add(createDashboardPage(), PAGE_DASHBOARD);
        pageHolder.add(createQueuePage(), PAGE_QUEUE);
        pageHolder.add(createStudentsPage(), PAGE_STUDENTS);
        pageHolder.add(createDisplayPage(), PAGE_DISPLAY);
        pageHolder.add(createReportsPage(), PAGE_REPORTS);
        right.add(pageHolder, BorderLayout.CENTER);

        right.add(createStatusBar(), BorderLayout.SOUTH);

        root.add(createSidebar(), BorderLayout.WEST);
        root.add(right, BorderLayout.CENTER);
        setContentPane(root);
    }

    /** The navy navigation sidebar. */
    private JPanel createSidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBackground(COLOR_SIDEBAR);
        sidebar.setPreferredSize(new Dimension(250, 100));

        // ----- Brand area -----
        JLabel brand = new JLabel("SMART QUEUE");
        brand.setFont(new Font(FONT_NAME, Font.BOLD, 24));
        brand.setForeground(Color.WHITE);
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel brandSub = new JLabel("College Office Token System");
        brandSub.setFont(FONT_SMALL);
        brandSub.setForeground(new Color(165, 180, 252));
        brandSub.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel brandPanel = new JPanel();
        brandPanel.setLayout(new BoxLayout(brandPanel, BoxLayout.Y_AXIS));
        brandPanel.setOpaque(false);
        brandPanel.setBorder(BorderFactory.createEmptyBorder(26, 24, 26, 12));
        brandPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        brandPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        brandPanel.add(brand);
        brandPanel.add(Box.createVerticalStrut(4));
        brandPanel.add(brandSub);
        sidebar.add(brandPanel);

        JLabel menuLabel = new JLabel("MENU");
        menuLabel.setFont(new Font(FONT_NAME, Font.BOLD, 12));
        menuLabel.setForeground(new Color(129, 140, 248));
        menuLabel.setBorder(BorderFactory.createEmptyBorder(8, 24, 8, 0));
        menuLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(menuLabel);

        // ----- Navigation buttons -----
        addNavButton(sidebar, PAGE_DASHBOARD, IconType.DASHBOARD);
        addNavButton(sidebar, PAGE_QUEUE, IconType.QUEUE);
        addNavButton(sidebar, PAGE_STUDENTS, IconType.STUDENTS);
        addNavButton(sidebar, PAGE_DISPLAY, IconType.DISPLAY);
        addNavButton(sidebar, PAGE_REPORTS, IconType.REPORTS);

        sidebar.add(Box.createVerticalGlue());

        // ----- Footer -----
        JLabel footer = new JLabel("<html>Records saved in<br>" + CSV_PATH.getFileName() + "</html>");
        footer.setFont(FONT_SMALL);
        footer.setForeground(new Color(129, 140, 248));
        footer.setBorder(BorderFactory.createEmptyBorder(12, 24, 20, 12));
        footer.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(footer);
        return sidebar;
    }

    /** Creates one sidebar button, adds it to the sidebar and remembers it. */
    private void addNavButton(JPanel sidebar, String pageName, IconType icon) {
        NavButton button = new NavButton(pageName, icon);
        button.addActionListener(e -> showPage(pageName));
        navButtons.add(button);
        sidebar.add(button);
    }

    /** The white bar above the pages with the page title and a live clock. */
    private JPanel createTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Color.WHITE);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, COLOR_BORDER),
                BorderFactory.createEmptyBorder(16, 24, 16, 24)));

        pageTitleLabel = new JLabel(PAGE_DASHBOARD);
        pageTitleLabel.setFont(new Font(FONT_NAME, Font.BOLD, 26));
        pageTitleLabel.setForeground(COLOR_NAVY);

        clockLabel = new JLabel(LocalDateTime.now().format(CLOCK_FORMAT));
        clockLabel.setFont(FONT_TEXT);
        clockLabel.setForeground(COLOR_GREY);

        // A Swing timer updates the clock every second (it runs on the correct thread for us).
        javax.swing.Timer clockTimer = new javax.swing.Timer(1000,
                e -> clockLabel.setText(LocalDateTime.now().format(CLOCK_FORMAT)));
        clockTimer.start();

        bar.add(pageTitleLabel, BorderLayout.WEST);
        bar.add(clockLabel, BorderLayout.EAST);
        return bar;
    }

    /** The thin message strip at the bottom of the window. */
    private JPanel createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Color.WHITE);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, COLOR_BORDER),
                BorderFactory.createEmptyBorder(8, 24, 8, 24)));
        statusBarLabel = new JLabel("Ready.");
        statusBarLabel.setFont(FONT_SMALL);
        statusBarLabel.setForeground(COLOR_GREY);
        bar.add(statusBarLabel, BorderLayout.CENTER);
        return bar;
    }

    /** Switches to a page and highlights its sidebar button. */
    private void showPage(String pageName) {
        cardLayout.show(pageHolder, pageName);
        pageTitleLabel.setText(pageName);
        for (NavButton button : navButtons) {
            button.setActive(button.getText().equals(pageName));
        }
        if (PAGE_QUEUE.equals(pageName) && nameField != null) {
            nameField.requestFocusInWindow();
        }
    }

    // ----- Page 1: Dashboard -----

    private JPanel createDashboardPage() {
        JPanel page = new JPanel(new BorderLayout(0, 20));
        page.setOpaque(false);

        // Four large cards
        totalCard = new StatCard("TOTAL STUDENTS", COLOR_NAVY, IconType.STUDENTS);
        waitingCard = new StatCard("WAITING", COLOR_ORANGE, IconType.QUEUE);
        servingCard = new StatCard("NOW SERVING", COLOR_BLUE, IconType.DISPLAY);
        completedCard = new StatCard("COMPLETED", COLOR_GREEN, IconType.CHECK);

        JPanel cards = new JPanel(new GridLayout(1, 4, 20, 0));
        cards.setOpaque(false);
        cards.setPreferredSize(new Dimension(100, 160));
        cards.add(totalCard);
        cards.add(waitingCard);
        cards.add(servingCard);
        cards.add(completedCard);

        // Middle: "Next in Line" list and a chart
        nextListPanel = new JPanel();
        nextListPanel.setLayout(new BoxLayout(nextListPanel, BoxLayout.Y_AXIS));
        nextListPanel.setOpaque(false);

        JPanel nextHolder = new JPanel(new BorderLayout());
        nextHolder.setOpaque(false);
        nextHolder.add(nextListPanel, BorderLayout.NORTH);

        waitingByDeptChart = new BarChartPanel();

        JPanel middle = new JPanel(new GridLayout(1, 2, 20, 0));
        middle.setOpaque(false);
        middle.add(createTitledCard("Next in Line", nextHolder));
        middle.add(createTitledCard("Waiting by Department", waitingByDeptChart));

        // Bottom: quick actions
        RoundedButton newTokenButton = new RoundedButton("New Token", COLOR_BLUE, IconType.PLUS);
        newTokenButton.addActionListener(e -> showPage(PAGE_QUEUE));
        RoundedButton callNextButton = new RoundedButton("Call Next", COLOR_BLUE, IconType.NEXT);
        callNextButton.addActionListener(e -> callNext());
        RoundedButton completeButton = new RoundedButton("Mark Completed", COLOR_GREEN, IconType.CHECK);
        completeButton.addActionListener(e -> markCompleted(null));
        RoundedButton refreshButton = new RoundedButton("Refresh", COLOR_GREY, IconType.REFRESH);
        refreshButton.addActionListener(e -> refreshRecords());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        actions.setOpaque(false);
        actions.add(newTokenButton);
        actions.add(callNextButton);
        actions.add(completeButton);
        actions.add(refreshButton);

        page.add(cards, BorderLayout.NORTH);
        page.add(middle, BorderLayout.CENTER);
        page.add(actions, BorderLayout.SOUTH);
        return page;
    }

    // ----- Page 2: Manage Queue -----

    private JPanel createQueuePage() {
        JPanel page = new JPanel(new BorderLayout(20, 0));
        page.setOpaque(false);
        page.add(createTokenFormCard(), BorderLayout.WEST);

        // "Now serving" banner
        servingBannerLabel = new JLabel(" ");
        servingBannerLabel.setFont(new Font(FONT_NAME, Font.BOLD, 18));
        servingBannerLabel.setForeground(new Color(30, 64, 175));
        JPanel banner = new JPanel(new BorderLayout());
        banner.setBackground(new Color(219, 234, 254));
        banner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(147, 197, 253)),
                BorderFactory.createEmptyBorder(14, 18, 14, 18)));
        banner.add(servingBannerLabel, BorderLayout.CENTER);

        // Action buttons
        RoundedButton callNextButton = new RoundedButton("Call Next", COLOR_BLUE, IconType.NEXT);
        callNextButton.addActionListener(e -> callNext());
        RoundedButton completeButton = new RoundedButton("Mark Completed", COLOR_GREEN, IconType.CHECK);
        completeButton.addActionListener(e -> markCompleted(queueView.getSelected()));
        RoundedButton refreshButton = new RoundedButton("Refresh", COLOR_GREY, IconType.REFRESH);
        refreshButton.addActionListener(e -> refreshRecords());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        buttons.setOpaque(false);
        buttons.add(callNextButton);
        buttons.add(completeButton);
        buttons.add(refreshButton);

        JPanel top = new JPanel(new BorderLayout(0, 12));
        top.setOpaque(false);
        top.add(banner, BorderLayout.NORTH);
        top.add(buttons, BorderLayout.SOUTH);

        queueView = new RecordsView(true); // only waiting + serving students

        JPanel right = new JPanel(new BorderLayout(0, 14));
        right.setOpaque(false);
        right.add(top, BorderLayout.NORTH);
        right.add(queueView, BorderLayout.CENTER);
        page.add(right, BorderLayout.CENTER);
        return page;
    }

    /** The "New Student" form card on the Manage Queue page. */
    private JPanel createTokenFormCard() {
        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_BORDER),
                BorderFactory.createEmptyBorder(22, 22, 22, 22)));

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL;

        JLabel heading = new JLabel("New Student");
        heading.setFont(new Font(FONT_NAME, Font.BOLD, 21));
        heading.setForeground(COLOR_NAVY);

        JLabel nameLabel = new JLabel("Student Name");
        nameLabel.setFont(FONT_BOLD);
        nameLabel.setForeground(COLOR_TEXT);

        nameField = new JTextField();
        styleTextField(nameField);
        nameField.setPreferredSize(new Dimension(260, 42));
        nameField.addActionListener(e -> generateToken()); // Enter key generates the token

        JLabel departmentLabel = new JLabel("Department");
        departmentLabel.setFont(FONT_BOLD);
        departmentLabel.setForeground(COLOR_TEXT);

        departmentBox = new JComboBox<>(DEPARTMENTS);
        departmentBox.setFont(FONT_TEXT);
        departmentBox.setPreferredSize(new Dimension(260, 42));

        RoundedButton generateButton = new RoundedButton("Generate Token", COLOR_BLUE, IconType.PLUS);
        generateButton.addActionListener(e -> generateToken());

        JLabel hint = new JLabel("<html><body style='width:240px'>Tokens are numbered in order "
                + "(T001, T002, ...) and stay unique even after the app is restarted.</body></html>");
        hint.setFont(FONT_SMALL);
        hint.setForeground(COLOR_GREY);

        gc.gridy = 0;
        gc.insets = new Insets(0, 0, 16, 0);
        card.add(heading, gc);
        gc.gridy = 1;
        gc.insets = new Insets(0, 0, 6, 0);
        card.add(nameLabel, gc);
        gc.gridy = 2;
        gc.insets = new Insets(0, 0, 14, 0);
        card.add(nameField, gc);
        gc.gridy = 3;
        gc.insets = new Insets(0, 0, 6, 0);
        card.add(departmentLabel, gc);
        gc.gridy = 4;
        gc.insets = new Insets(0, 0, 20, 0);
        card.add(departmentBox, gc);
        gc.gridy = 5;
        gc.insets = new Insets(0, 0, 14, 0);
        card.add(generateButton, gc);
        gc.gridy = 6;
        gc.insets = new Insets(0, 0, 0, 0);
        card.add(hint, gc);

        // The wrapper keeps the card at the top and fixes the column width.
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setPreferredSize(new Dimension(340, 10));
        wrapper.add(card, BorderLayout.NORTH);
        return wrapper;
    }

    // ----- Page 3: Students -----

    private JPanel createStudentsPage() {
        studentsView = new RecordsView(false); // every record, with a status filter
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        page.add(studentsView, BorderLayout.CENTER);
        return page;
    }

    // ----- Page 4: Token Display -----

    private JPanel createDisplayPage() {
        tokenDisplay = new TokenDisplayPanel();

        RoundedButton callNextButton = new RoundedButton("Call Next", COLOR_BLUE, IconType.NEXT);
        callNextButton.addActionListener(e -> callNext());
        RoundedButton completeButton = new RoundedButton("Mark Completed", COLOR_GREEN, IconType.CHECK);
        completeButton.addActionListener(e -> markCompleted(null));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
        controls.setOpaque(false);
        controls.add(callNextButton);
        controls.add(completeButton);

        JPanel page = new JPanel(new BorderLayout(0, 16));
        page.setOpaque(false);
        page.add(tokenDisplay, BorderLayout.CENTER);
        page.add(controls, BorderLayout.SOUTH);
        return page;
    }

    // ----- Page 5: Reports -----

    private JPanel createReportsPage() {
        JPanel page = new JPanel(new BorderLayout(0, 16));
        page.setOpaque(false);

        JLabel intro = new JLabel("Live summary of every token in the system");
        intro.setFont(FONT_TEXT);
        intro.setForeground(COLOR_GREY);
        RoundedButton refreshButton = new RoundedButton("Refresh", COLOR_GREY, IconType.REFRESH);
        refreshButton.addActionListener(e -> refreshRecords());
        JPanel strip = new JPanel(new BorderLayout());
        strip.setOpaque(false);
        strip.add(intro, BorderLayout.WEST);
        strip.add(refreshButton, BorderLayout.EAST);

        // Department-wise table
        deptReportModel = new DefaultTableModel(
                new String[]{"Department", "Waiting", "Serving", "Completed", "Total"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable deptTable = createStyledTable(deptReportModel, -1, 0, 0);
        // Give the Department column more room so "Examination Cell" is not cut off.
        int[] reportColumnWidths = {170, 85, 85, 115, 80};
        for (int column = 0; column < reportColumnWidths.length; column++) {
            deptTable.getColumnModel().getColumn(column).setPreferredWidth(reportColumnWidths[column]);
        }

        // Status-wise table
        statusReportModel = new DefaultTableModel(new String[]{"Status", "Count", "Share"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable statusTable = createStyledTable(statusReportModel, 0, -1, 0);

        deptTotalsChart = new BarChartPanel();
        statusChart = new BarChartPanel();

        JPanel grid = new JPanel(new GridLayout(2, 2, 20, 20));
        grid.setOpaque(false);
        grid.add(createTitledCard("Department-wise Report", wrapInScroll(deptTable)));
        grid.add(createTitledCard("Status-wise Report", wrapInScroll(statusTable)));
        grid.add(createTitledCard("Total Tokens by Department", deptTotalsChart));
        grid.add(createTitledCard("Tokens by Status", statusChart));

        page.add(strip, BorderLayout.NORTH);
        page.add(grid, BorderLayout.CENTER);
        return page;
    }

    // ----- Shared interface helpers -----

    /** A white card with a heading and some content. */
    private static JPanel createTitledCard(String title, JComponent content) {
        JPanel card = new JPanel(new BorderLayout(0, 12));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_BORDER),
                BorderFactory.createEmptyBorder(18, 20, 18, 20)));
        JLabel heading = new JLabel(title);
        heading.setFont(new Font(FONT_NAME, Font.BOLD, 18));
        heading.setForeground(COLOR_NAVY);
        card.add(heading, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    /** Gives a text field a thin border and inner padding so typed text is easy to read. */
    private static void styleTextField(JTextField field) {
        field.setFont(FONT_TEXT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(203, 213, 225)),
                BorderFactory.createEmptyBorder(0, 12, 0, 12)));
    }

    /** Puts a table into a scroll pane with a white background. */
    private static JScrollPane wrapInScroll(JTable table) {
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(COLOR_BORDER));
        scroll.getViewport().setBackground(Color.WHITE);
        return scroll;
    }

    /**
     * Creates a table with the application's look: tall rows, no grid lines, navy header,
     * coloured status cells and striped rows.
     *
     * @param statusColumn column that holds a status text to colour (or -1 for none)
     * @param boldColumn   column to show in bold (or -1 for none)
     * @param leftColumns  columns that should be left aligned (the rest are centred)
     */
    private static JTable createStyledTable(DefaultTableModel model, int statusColumn,
                                            int boldColumn, int... leftColumns) {
        Set<Integer> left = new HashSet<>();
        for (int column : leftColumns) {
            left.add(column);
        }

        JTable t = new JTable(model);
        t.setFont(FONT_TEXT);
        t.setRowHeight(42);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setSelectionBackground(new Color(191, 219, 254));
        t.setSelectionForeground(COLOR_TEXT);
        t.setFillsViewportHeight(true);
        t.setDefaultRenderer(Object.class, new StyledRenderer(statusColumn, boldColumn, left));

        JTableHeader header = t.getTableHeader();
        header.setReorderingAllowed(false);
        header.setPreferredSize(new Dimension(100, 44));
        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);
                label.setOpaque(true);
                label.setBackground(COLOR_NAVY);
                label.setForeground(Color.WHITE);
                label.setFont(FONT_BOLD);
                label.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
                label.setHorizontalAlignment(left.contains(column) ? SwingConstants.LEFT : SwingConstants.CENTER);
                return label;
            }
        });
        return t;
    }

    // =====================================================================
    // 4. ACTIONS (BUTTON CLICKS)
    // =====================================================================

    /** Generate Token: validates the form, creates a WAITING entry and saves it. */
    private void generateToken() {
        // Tidy the name: trim it and turn runs of spaces/tabs into a single space.
        String name = nameField.getText().trim().replaceAll("\\s+", " ");
        String department = (String) departmentBox.getSelectedItem();

        // ----- Validation -----
        if (name.isEmpty()) {
            showError("Please enter the student's name before generating a token.");
            nameField.requestFocusInWindow();
            return;
        }
        if (name.length() > MAX_NAME_LENGTH) {
            showError("The name is too long. Please use at most " + MAX_NAME_LENGTH + " characters.");
            nameField.requestFocusInWindow();
            return;
        }
        if (!containsLetter(name)) {
            showError("The name must contain at least one letter.");
            nameField.requestFocusInWindow();
            return;
        }
        if (department == null) {
            showError("Please select a department.");
            return;
        }

        // Warn if the same student already has an active token in this department.
        QueueEntry existing = findActiveEntry(name, department);
        if (existing != null) {
            int choice = JOptionPane.showConfirmDialog(this,
                    name + " already has an active token (" + existing.getTokenText() + ") in "
                            + department + ".\nDo you still want to generate another token?",
                    "Possible duplicate", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }

        // The next number is always (highest existing number + 1), so tokens stay unique
        // even after the program is closed and opened again.
        QueueEntry entry = new QueueEntry(getNextTokenNumber(), name, department, Status.WAITING,
                LocalDateTime.now().format(TIME_FORMAT));
        entries.add(entry);

        if (!saveRecords()) {
            entries.remove(entry); // saving failed: undo so memory and file stay consistent
            return;
        }

        nameField.setText("");
        nameField.requestFocusInWindow();
        queueView.clearFilters(); // make sure the new row is visible
        refreshAll();
        queueView.select(entry);

        int waitingAhead = countByStatus(Status.WAITING) - 1;
        setStatus("Token " + entry.getTokenText() + " created for " + name + ".");
        JOptionPane.showMessageDialog(this,
                "Token  " + entry.getTokenText() + "  generated for " + name + "\n"
                        + "Department: " + department + "\n"
                        + "People waiting ahead: " + waitingAhead,
                "Token Generated", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Call Next: serves the oldest waiting token. */
    private void callNext() {
        QueueEntry serving = findFirstByStatus(Status.SERVING);
        if (serving != null) {
            showWarning("Token " + serving.getTokenText() + " (" + serving.getName()
                    + ") is currently being served.\nPlease mark it as completed before calling the next token.");
            return;
        }

        QueueEntry next = findFirstByStatus(Status.WAITING);
        if (next == null) {
            showInfo("There are no waiting students in the queue.");
            return;
        }

        next.setStatus(Status.SERVING);
        if (!saveRecords()) {
            next.setStatus(Status.WAITING); // undo, saving failed
            return;
        }

        refreshAll();
        queueView.select(next);
        setStatus("Now serving " + next.getTokenText() + " - " + next.getName()
                + " (" + next.getDepartment() + ").");
    }

    /**
     * Mark Completed.
     * If a specific entry is given (a selected table row) that one is completed.
     * If null is given, the token currently being served is completed.
     */
    private void markCompleted(QueueEntry chosen) {
        QueueEntry target = chosen;

        if (target == null) {
            target = findFirstByStatus(Status.SERVING);
            if (target == null) {
                showWarning("No token is currently being served.\nUse Call Next first.");
                return;
            }
        } else if (target.getStatus() == Status.COMPLETED) {
            showWarning("Token " + target.getTokenText() + " is already completed.");
            return;
        } else if (target.getStatus() == Status.WAITING) {
            showWarning("Token " + target.getTokenText() + " is still waiting.\n"
                    + "It must be called (Call Next) before it can be completed.");
            return;
        }

        target.setStatus(Status.COMPLETED);
        if (!saveRecords()) {
            target.setStatus(Status.SERVING); // undo, saving failed
            return;
        }

        refreshAll();
        setStatus("Token " + target.getTokenText() + " (" + target.getName() + ") completed.");
    }

    /** Refresh: reloads everything from the CSV file. */
    private void refreshRecords() {
        try {
            List<QueueEntry> loaded = readRecordsFromFile();
            entries.clear();
            entries.addAll(loaded);
            if (skippedLines > 0) {
                setStatus("Refreshed. " + entries.size() + " record(s) loaded, "
                        + skippedLines + " invalid line(s) skipped.");
            } else {
                setStatus("Refreshed. " + entries.size() + " record(s) loaded.");
            }
        } catch (IOException ex) {
            showError("Could not read " + CSV_PATH.toAbsolutePath() + "\n\n" + ex.getMessage()
                    + "\n\nShowing the data already in memory instead.");
        }
        refreshAll();
    }

    // =====================================================================
    // 5. UPDATING EVERY SCREEN FROM THE DATA
    // =====================================================================

    /** Redraws all pages. Called after every change. */
    private void refreshAll() {
        queueView.refresh();
        studentsView.refresh();
        updateDashboard();
        updateServingBanner();
        updateTokenDisplay();
        updateReports();
    }

    private void updateDashboard() {
        int total = entries.size();
        int waiting = countByStatus(Status.WAITING);
        int completed = countByStatus(Status.COMPLETED);
        QueueEntry serving = findFirstByStatus(Status.SERVING);

        totalCard.setContent(String.valueOf(total), total == 0 ? "no tokens issued yet" : "tokens issued so far");
        waitingCard.setContent(String.valueOf(waiting), waiting == 0 ? "the queue is empty" : "students waiting");
        if (serving == null) {
            servingCard.setContent("--", "nobody is being served");
        } else {
            servingCard.setContent(serving.getTokenText(), serving.getName() + "  -  " + serving.getDepartment());
        }
        completedCard.setContent(String.valueOf(completed),
                total == 0 ? "no tokens yet" : (completed * 100 / total) + "% of all tokens");

        // "Next in Line" list (up to 5 waiting students)
        nextListPanel.removeAll();
        List<QueueEntry> next = getWaitingEntries(5);
        if (next.isEmpty()) {
            JLabel empty = new JLabel("No students are waiting.");
            empty.setFont(FONT_TEXT);
            empty.setForeground(COLOR_GREY);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            nextListPanel.add(empty);
        } else {
            for (QueueEntry entry : next) {
                nextListPanel.add(createNextRow(entry));
                nextListPanel.add(Box.createVerticalStrut(8));
            }
        }
        nextListPanel.revalidate();
        nextListPanel.repaint();

        // Chart: waiting students per department
        int[] waitingCounts = new int[DEPARTMENTS.length];
        for (int i = 0; i < DEPARTMENTS.length; i++) {
            waitingCounts[i] = countBy(DEPARTMENTS[i], Status.WAITING);
        }
        waitingByDeptChart.setData(DEPARTMENTS, waitingCounts, new Color[]{COLOR_ORANGE});
    }

    /** One row of the "Next in Line" list: token, name, department. */
    private JPanel createNextRow(QueueEntry entry) {
        JPanel row = new JPanel(new BorderLayout(14, 0));
        row.setBackground(new Color(248, 250, 252));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_BORDER),
                BorderFactory.createEmptyBorder(8, 14, 8, 14)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel token = new JLabel(entry.getTokenText());
        token.setFont(new Font(FONT_NAME, Font.BOLD, 17));
        token.setForeground(COLOR_BLUE);
        token.setPreferredSize(new Dimension(70, 24));

        JLabel name = new JLabel(entry.getName());
        name.setFont(FONT_TEXT);
        name.setForeground(COLOR_TEXT);

        JLabel department = new JLabel(entry.getDepartment());
        department.setFont(FONT_SMALL);
        department.setForeground(COLOR_GREY);

        row.add(token, BorderLayout.WEST);
        row.add(name, BorderLayout.CENTER);
        row.add(department, BorderLayout.EAST);
        return row;
    }

    private void updateServingBanner() {
        QueueEntry serving = findFirstByStatus(Status.SERVING);
        if (serving == null) {
            servingBannerLabel.setText("No token is being served.  Click Call Next to begin.");
        } else {
            servingBannerLabel.setText("Now serving  " + serving.getTokenText() + "  -  "
                    + serving.getName() + "  (" + serving.getDepartment() + ")");
        }
    }

    private void updateTokenDisplay() {
        tokenDisplay.setState(findFirstByStatus(Status.SERVING), getWaitingEntries(5),
                countByStatus(Status.WAITING));
    }

    private void updateReports() {
        // ----- Department-wise table -----
        deptReportModel.setRowCount(0);
        int[] deptTotals = new int[DEPARTMENTS.length];
        int sumWaiting = 0;
        int sumServing = 0;
        int sumCompleted = 0;
        for (int i = 0; i < DEPARTMENTS.length; i++) {
            int w = countBy(DEPARTMENTS[i], Status.WAITING);
            int s = countBy(DEPARTMENTS[i], Status.SERVING);
            int c = countBy(DEPARTMENTS[i], Status.COMPLETED);
            deptTotals[i] = w + s + c;
            sumWaiting += w;
            sumServing += s;
            sumCompleted += c;
            deptReportModel.addRow(new Object[]{DEPARTMENTS[i], String.valueOf(w), String.valueOf(s),
                    String.valueOf(c), String.valueOf(w + s + c)});
        }
        deptReportModel.addRow(new Object[]{"TOTAL", String.valueOf(sumWaiting), String.valueOf(sumServing),
                String.valueOf(sumCompleted), String.valueOf(sumWaiting + sumServing + sumCompleted)});

        // ----- Status-wise table -----
        statusReportModel.setRowCount(0);
        int total = entries.size();
        Status[] statuses = Status.values();
        int[] statusCounts = new int[statuses.length];
        String[] statusLabels = new String[statuses.length];
        for (int i = 0; i < statuses.length; i++) {
            statusCounts[i] = countByStatus(statuses[i]);
            statusLabels[i] = statuses[i].getLabel();
            int percent = total == 0 ? 0 : Math.round(100f * statusCounts[i] / total);
            statusReportModel.addRow(new Object[]{statusLabels[i], String.valueOf(statusCounts[i]), percent + "%"});
        }
        statusReportModel.addRow(new Object[]{"TOTAL", String.valueOf(total), total == 0 ? "0%" : "100%"});

        // ----- Charts -----
        deptTotalsChart.setData(DEPARTMENTS, deptTotals, new Color[]{COLOR_BLUE});
        statusChart.setData(statusLabels, statusCounts, new Color[]{COLOR_ORANGE, COLOR_BLUE, COLOR_GREEN});
    }

    // =====================================================================
    // 6. SMALL QUERY HELPERS AND DIALOGS
    // =====================================================================

    /** Counts how many entries have the given status. */
    private int countByStatus(Status status) {
        int count = 0;
        for (QueueEntry entry : entries) {
            if (entry.getStatus() == status) {
                count++;
            }
        }
        return count;
    }

    /** Counts entries of one department with one status. */
    private int countBy(String department, Status status) {
        int count = 0;
        for (QueueEntry entry : entries) {
            if (entry.getDepartment().equals(department) && entry.getStatus() == status) {
                count++;
            }
        }
        return count;
    }

    /** Returns the first (oldest) entry with the given status, or null if none. */
    private QueueEntry findFirstByStatus(Status status) {
        for (QueueEntry entry : entries) {
            if (entry.getStatus() == status) {
                return entry;
            }
        }
        return null;
    }

    /** Returns up to 'limit' waiting entries, oldest first. */
    private List<QueueEntry> getWaitingEntries(int limit) {
        List<QueueEntry> result = new ArrayList<>();
        for (QueueEntry entry : entries) {
            if (entry.getStatus() == Status.WAITING) {
                result.add(entry);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    /** Finds a WAITING or SERVING entry with the same name and department (ignoring case). */
    private QueueEntry findActiveEntry(String name, String department) {
        for (QueueEntry entry : entries) {
            boolean active = entry.getStatus() != Status.COMPLETED;
            if (active && entry.getName().equalsIgnoreCase(name)
                    && entry.getDepartment().equals(department)) {
                return entry;
            }
        }
        return null;
    }

    /** The next token number = highest number used so far + 1 (starts at 1). */
    private int getNextTokenNumber() {
        int highest = 0;
        for (QueueEntry entry : entries) {
            if (entry.getNumber() > highest) {
                highest = entry.getNumber();
            }
        }
        return highest + 1;
    }

    /** True if the text has at least one letter in it. */
    private static boolean containsLetter(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private void setStatus(String message) {
        statusBarLabel.setText(message);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void showWarning(String message) {
        JOptionPane.showMessageDialog(this, message, "Not allowed", JOptionPane.WARNING_MESSAGE);
    }

    private void showInfo(String message) {
        JOptionPane.showMessageDialog(this, message, "Information", JOptionPane.INFORMATION_MESSAGE);
    }

    // =====================================================================
    // 7. CSV SAVING AND LOADING (same format as version 1)
    // =====================================================================

    /**
     * Saves all entries to queue_records.csv.
     * The data is first written to a temporary file and then moved over the real file,
     * so the CSV is never left half-written.
     *
     * @return true if saving worked, false if an error happened (an error dialog is shown)
     */
    private boolean saveRecords() {
        List<String> lines = new ArrayList<>();
        lines.add(CSV_HEADER);
        for (QueueEntry entry : entries) {
            lines.add(csvEscape(entry.getTokenText()) + ","
                    + csvEscape(entry.getName()) + ","
                    + csvEscape(entry.getDepartment()) + ","
                    + csvEscape(entry.getStatus().name()) + ","
                    + csvEscape(entry.getCreated()));
        }

        Path tempPath = Paths.get(CSV_PATH.toString() + ".tmp");
        try {
            Files.write(tempPath, lines, StandardCharsets.UTF_8);
            Files.move(tempPath, CSV_PATH, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ex) {
            showError("Could not save " + CSV_PATH.toAbsolutePath() + "\n\n" + ex.getMessage()
                    + "\n\nIf the file is open in Excel, please close it and try again.");
            return false;
        }
    }

    /**
     * Reads queue_records.csv and returns the records found in it.
     * Broken lines (wrong number of fields, unknown status, bad token...) are skipped
     * and counted in skippedLines. A missing file simply means "no records yet".
     */
    private List<QueueEntry> readRecordsFromFile() throws IOException {
        List<QueueEntry> loaded = new ArrayList<>();
        skippedLines = 0;

        if (!Files.exists(CSV_PATH)) {
            return loaded;
        }

        List<String> lines = Files.readAllLines(CSV_PATH, StandardCharsets.UTF_8);
        Set<Integer> usedNumbers = new HashSet<>();
        boolean firstLine = true;

        for (String line : lines) {
            // Remove the invisible "BOM" character that Excel sometimes adds at the start.
            if (firstLine) {
                firstLine = false;
                if (line.startsWith("﻿")) {
                    line = line.substring(1);
                }
                if (line.toLowerCase().startsWith("token,")) {
                    continue; // this is the header line
                }
            }
            if (line.trim().isEmpty()) {
                continue; // ignore blank lines
            }

            List<String> fields = parseCsvLine(line);
            if (fields.size() < 4) {
                skippedLines++;
                continue;
            }

            String token = fields.get(0).trim().toUpperCase();
            String name = fields.get(1).trim();
            String department = fields.get(2).trim();
            Status status = Status.fromText(fields.get(3).trim());
            String created = fields.size() > 4 ? fields.get(4).trim() : "";

            // The token must look like T followed by 1-9 digits, e.g. T001.
            if (!token.matches("T\\d{1,9}") || name.isEmpty() || department.isEmpty() || status == null) {
                skippedLines++;
                continue;
            }

            int number = Integer.parseInt(token.substring(1));
            if (!usedNumbers.add(number)) {
                skippedLines++; // duplicate token number: keep only the first one
                continue;
            }

            loaded.add(new QueueEntry(number, name, department, status, created));
        }

        // Keep the queue in token order (oldest first).
        loaded.sort(Comparator.comparingInt(QueueEntry::getNumber));
        return loaded;
    }

    /**
     * Makes one value safe for a CSV file.
     * If it contains a comma, a double quote or a line break, it is wrapped in quotes,
     * and any quote inside it is doubled.  Example:  Smith, John  ->  "Smith, John"
     */
    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return needsQuotes ? "\"" + escaped + "\"" : escaped;
    }

    /**
     * Splits one CSV line into its fields, understanding quoted fields.
     * Example:  T001,"Smith, John",Accounts  ->  [T001] [Smith, John] [Accounts]
     */
    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean insideQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (insideQuotes) {
                if (c == '"') {
                    // Two quotes in a row ("") mean one real quote character.
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        insideQuotes = false; // closing quote
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    insideQuotes = true; // opening quote
                } else if (c == ',') {
                    fields.add(current.toString()); // end of this field
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString()); // the last field
        return fields;
    }

    // =====================================================================
    // 8. HELPER CLASSES
    // =====================================================================

    // ---------------------------------------------------------------------
    // 8a. Data classes
    // ---------------------------------------------------------------------

    /** The three possible states of a token. */
    private enum Status {
        WAITING("Waiting"),
        SERVING("Serving"),
        COMPLETED("Completed");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        /** The text shown in tables. */
        String getLabel() {
            return label;
        }

        /** Converts text from the CSV file to a Status. Returns null if it is not recognised. */
        static Status fromText(String text) {
            for (Status s : values()) {
                if (s.name().equalsIgnoreCase(text) || s.label.equalsIgnoreCase(text)) {
                    return s;
                }
            }
            return null;
        }
    }

    /** One student's record in the queue. */
    private static class QueueEntry {
        private final int number;           // 1, 2, 3 ... (shown as T001, T002, T003)
        private final String name;
        private final String department;
        private Status status;
        private final String created;       // time the token was generated

        QueueEntry(int number, String name, String department, Status status, String created) {
            this.number = number;
            this.name = name;
            this.department = department;
            this.status = status;
            this.created = created;
        }

        int getNumber() {
            return number;
        }

        /** The token as text, for example T007. */
        String getTokenText() {
            return String.format("T%03d", number);
        }

        String getName() {
            return name;
        }

        String getDepartment() {
            return department;
        }

        Status getStatus() {
            return status;
        }

        void setStatus(Status status) {
            this.status = status;
        }

        String getCreated() {
            return created;
        }

        /** True if the search text appears in the token, name, department or status. */
        boolean matches(String filter) {
            if (filter == null || filter.isEmpty()) {
                return true;
            }
            String f = filter.toLowerCase();
            return getTokenText().toLowerCase().contains(f)
                    || name.toLowerCase().contains(f)
                    || department.toLowerCase().contains(f)
                    || status.getLabel().toLowerCase().contains(f);
        }
    }

    // ---------------------------------------------------------------------
    // 8b. Icons (drawn with Java2D, so no image files are needed)
    // ---------------------------------------------------------------------

    private enum IconType {
        DASHBOARD, QUEUE, STUDENTS, DISPLAY, REPORTS, PLUS, NEXT, CHECK, REFRESH, SEARCH, CLOSE
    }

    /** A small vector icon. */
    private static class VectorIcon implements Icon {
        private final IconType type;
        private final int size;
        private final Color color;

        VectorIcon(IconType type, int size, Color color) {
            this.type = type;
            this.size = size;
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);
            g2.setColor(color);
            float s = size;
            g2.setStroke(new BasicStroke(Math.max(1.6f, s / 10f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            switch (type) {
                case DASHBOARD: {
                    float a = s * 0.42f;
                    g2.fill(new RoundRectangle2D.Float(0, 0, a, a, 4, 4));
                    g2.fill(new RoundRectangle2D.Float(s - a, 0, a, a, 4, 4));
                    g2.fill(new RoundRectangle2D.Float(0, s - a, a, a, 4, 4));
                    g2.fill(new RoundRectangle2D.Float(s - a, s - a, a, a, 4, 4));
                    break;
                }
                case QUEUE: {
                    for (int i = 0; i < 3; i++) {
                        float yy = s * (0.2f + 0.3f * i);
                        g2.fill(new Ellipse2D.Float(0, yy - 1.8f, 3.6f, 3.6f));
                        g2.draw(new Line2D.Float(s * 0.3f, yy, s, yy));
                    }
                    break;
                }
                case STUDENTS: {
                    g2.fill(new Ellipse2D.Float(s * 0.32f, s * 0.02f, s * 0.36f, s * 0.36f));
                    g2.fill(new Arc2D.Float(s * 0.1f, s * 0.5f, s * 0.8f, s * 0.9f, 0, 180, Arc2D.PIE));
                    break;
                }
                case DISPLAY: {
                    g2.draw(new RoundRectangle2D.Float(1, s * 0.1f, s - 2, s * 0.62f, 5, 5));
                    g2.draw(new Line2D.Float(s * 0.5f, s * 0.72f, s * 0.5f, s * 0.9f));
                    g2.draw(new Line2D.Float(s * 0.28f, s * 0.9f, s * 0.72f, s * 0.9f));
                    break;
                }
                case REPORTS: {
                    for (int i = 0; i < 3; i++) {
                        float barHeight = s * (0.4f + 0.3f * i);
                        g2.fill(new RoundRectangle2D.Float(s * (0.04f + 0.34f * i), s - barHeight,
                                s * 0.24f, barHeight, 3, 3));
                    }
                    break;
                }
                case PLUS: {
                    g2.draw(new Line2D.Float(s * 0.5f, s * 0.15f, s * 0.5f, s * 0.85f));
                    g2.draw(new Line2D.Float(s * 0.15f, s * 0.5f, s * 0.85f, s * 0.5f));
                    break;
                }
                case NEXT: {
                    Path2D.Float triangle = new Path2D.Float();
                    triangle.moveTo(s * 0.25f, s * 0.12f);
                    triangle.lineTo(s * 0.88f, s * 0.5f);
                    triangle.lineTo(s * 0.25f, s * 0.88f);
                    triangle.closePath();
                    g2.fill(triangle);
                    break;
                }
                case CHECK: {
                    Path2D.Float tick = new Path2D.Float();
                    tick.moveTo(s * 0.1f, s * 0.55f);
                    tick.lineTo(s * 0.38f, s * 0.82f);
                    tick.lineTo(s * 0.9f, s * 0.2f);
                    g2.draw(tick);
                    break;
                }
                case REFRESH: {
                    g2.draw(new Arc2D.Float(s * 0.12f, s * 0.12f, s * 0.76f, s * 0.76f, 50, 280, Arc2D.OPEN));
                    // Arrow head at the end of the arc (angle 330 degrees).
                    double r = s * 0.38;
                    double ex = s / 2.0 + r * Math.cos(Math.toRadians(330));
                    double ey = s / 2.0 - r * Math.sin(Math.toRadians(330));
                    double dirX = 0.5;
                    double dirY = -0.866;
                    double perpX = 0.866;
                    double perpY = 0.5;
                    double headLength = s * 0.24;
                    double headWidth = s * 0.17;
                    Path2D.Double head = new Path2D.Double();
                    head.moveTo(ex + dirX * headLength, ey + dirY * headLength);
                    head.lineTo(ex + perpX * headWidth, ey + perpY * headWidth);
                    head.lineTo(ex - perpX * headWidth, ey - perpY * headWidth);
                    head.closePath();
                    g2.fill(head);
                    break;
                }
                case SEARCH: {
                    g2.draw(new Ellipse2D.Float(s * 0.08f, s * 0.08f, s * 0.58f, s * 0.58f));
                    g2.draw(new Line2D.Float(s * 0.58f, s * 0.58f, s * 0.92f, s * 0.92f));
                    break;
                }
                case CLOSE: {
                    g2.draw(new Line2D.Float(s * 0.2f, s * 0.2f, s * 0.8f, s * 0.8f));
                    g2.draw(new Line2D.Float(s * 0.8f, s * 0.2f, s * 0.2f, s * 0.8f));
                    break;
                }
                default:
                    break;
            }
            g2.dispose();
        }
    }

    // ---------------------------------------------------------------------
    // 8c. Buttons
    // ---------------------------------------------------------------------

    /** A sidebar navigation button with an icon, hover colour and an "active page" highlight. */
    private static class NavButton extends JButton {
        private boolean active = false;
        private boolean hovering = false;

        NavButton(String text, IconType iconType) {
            super(text);
            setIcon(new VectorIcon(iconType, 20, new Color(199, 210, 254)));
            setIconTextGap(16);
            setHorizontalAlignment(SwingConstants.LEFT);
            setFont(new Font(FONT_NAME, Font.BOLD, 16));
            setForeground(Color.WHITE);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setBorder(BorderFactory.createEmptyBorder(0, 26, 0, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(250, 52));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            setAlignmentX(Component.LEFT_ALIGNMENT);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovering = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovering = false;
                    repaint();
                }
            });
        }

        void setActive(boolean active) {
            this.active = active;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            if (active) {
                g2.setColor(COLOR_SIDEBAR_ACTIVE);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(96, 165, 250));
                g2.fillRect(0, 0, 5, getHeight());
            } else if (hovering) {
                g2.setColor(COLOR_SIDEBAR_HOVER);
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            g2.dispose();
            super.paintComponent(g); // draws the icon and the text
        }
    }

    /** A button with rounded corners, an optional icon and a hover colour. */
    private static class RoundedButton extends JButton {
        private final Color normalColor;
        private final Color hoverColor;
        private boolean hovering = false;

        RoundedButton(String text, Color color, IconType iconType) {
            super(text);
            this.normalColor = color;
            this.hoverColor = color.darker();

            if (iconType != null) {
                setIcon(new VectorIcon(iconType, 16, Color.WHITE));
                setIconTextGap(9);
            }
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setForeground(Color.WHITE);
            setFont(FONT_BOLD);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(11, 22, 11, 22));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovering = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovering = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(hovering ? hoverColor : normalColor);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            g2.dispose();
            super.paintComponent(g); // draws the icon and the text
        }
    }

    // ---------------------------------------------------------------------
    // 8d. Dashboard card, token display and bar chart (custom painted)
    // ---------------------------------------------------------------------

    /** A large dashboard card: title, big number, small description and an icon. */
    private static class StatCard extends JPanel {
        private final String title;
        private final Color accent;
        private final IconType iconType;
        private String value = "0";
        private String subtitle = " ";

        StatCard(String title, Color accent, IconType iconType) {
            this.title = title;
            this.accent = accent;
            this.iconType = iconType;
            setOpaque(false);
            setPreferredSize(new Dimension(240, 160));
        }

        void setContent(String value, String subtitle) {
            this.value = value;
            this.subtitle = subtitle;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth() - 4;
            int h = getHeight() - 6;

            // Soft shadow, then the white card
            g2.setColor(new Color(15, 23, 70, 20));
            g2.fill(new RoundRectangle2D.Float(2, 5, w, h, 24, 24));
            RoundRectangle2D.Float shape = new RoundRectangle2D.Float(0, 0, w, h, 24, 24);
            g2.setColor(Color.WHITE);
            g2.fill(shape);

            // Coloured stripe on the left edge (clipped to the rounded shape)
            g2.setClip(shape);
            g2.setColor(accent);
            g2.fillRect(0, 0, 8, h);
            g2.setClip(null);

            // Icon inside a pale circle at the top right
            int circle = Math.max(44, Math.min(70, (int) (h * 0.42)));
            int circleX = w - circle - 22;
            int circleY = 22;
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 32));
            g2.fillOval(circleX, circleY, circle, circle);
            int iconSize = circle / 2;
            new VectorIcon(iconType, iconSize, accent).paintIcon(this, g2,
                    circleX + (circle - iconSize) / 2, circleY + (circle - iconSize) / 2);

            int pad = 28;

            // Title
            g2.setColor(COLOR_GREY);
            g2.setFont(new Font(FONT_NAME, Font.BOLD, 14));
            g2.drawString(title, pad, 36);

            // Big value
            int valueSize = Math.max(34, Math.min(66, (int) (h * 0.36)));
            g2.setColor(accent);
            g2.setFont(new Font(FONT_NAME, Font.BOLD, valueSize));
            g2.drawString(value, pad, (int) (h * 0.66));

            // Subtitle (shortened with "..." if too long)
            g2.setColor(COLOR_GREY);
            Font subFont = new Font(FONT_NAME, Font.PLAIN, 14);
            g2.setFont(subFont);
            g2.drawString(truncate(subtitle, g2.getFontMetrics(), w - pad * 2), pad, h - 20);

            g2.dispose();
        }
    }

    /** The large "waiting hall" screen: current token in huge type, then the next waiting tokens. */
    private static class TokenDisplayPanel extends JPanel {
        private QueueEntry serving = null;
        private List<QueueEntry> nextEntries = new ArrayList<>();
        private int waitingCount = 0;

        TokenDisplayPanel() {
            setOpaque(false);
            setPreferredSize(new Dimension(600, 400));
        }

        void setState(QueueEntry serving, List<QueueEntry> nextEntries, int waitingCount) {
            this.serving = serving;
            this.nextEntries = new ArrayList<>(nextEntries);
            this.waitingCount = waitingCount;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            // Background: navy gradient. Everything below is sized from w and h,
            // so the display looks right on any screen size.
            g2.setPaint(new GradientPaint(0, 0, new Color(12, 19, 60), 0, h, new Color(33, 46, 128)));
            g2.fillRoundRect(0, 0, w, h, 28, 28);

            // Heading
            drawCentered(g2, "NOW SERVING", boldFont(h * 0.045f), w / 2, (int) (h * 0.12),
                    new Color(147, 197, 253));

            // The big token
            String tokenText = serving == null ? "---" : serving.getTokenText();
            float tokenSize = Math.min(h * 0.34f, w * 0.2f);
            drawCentered(g2, tokenText, boldFont(tokenSize), w / 2, (int) (h * 0.45), Color.WHITE);

            // Name + department (or a friendly message)
            String line1;
            String line2;
            if (serving != null) {
                line1 = serving.getName();
                line2 = serving.getDepartment();
            } else if (waitingCount > 0) {
                line1 = "Please wait for your token to be called";
                line2 = " ";
            } else {
                line1 = "The queue is empty";
                line2 = " ";
            }
            Font nameFont = boldFont(h * 0.06f);
            g2.setFont(nameFont);
            line1 = truncate(line1, g2.getFontMetrics(), (int) (w * 0.85));
            drawCentered(g2, line1, nameFont, w / 2, (int) (h * 0.56), new Color(224, 231, 255));
            drawCentered(g2, line2, plainFont(h * 0.04f), w / 2, (int) (h * 0.62), new Color(165, 180, 252));

            // Divider line
            g2.setColor(new Color(255, 255, 255, 50));
            g2.fillRect((int) (w * 0.1), (int) (h * 0.67), (int) (w * 0.8), 2);

            // "Next in line" label
            drawCentered(g2, "NEXT IN LINE", boldFont(h * 0.035f), w / 2, (int) (h * 0.74),
                    new Color(147, 197, 253));

            // The next waiting tokens, drawn as small cards
            int n = Math.min(nextEntries.size(), 5);
            if (n == 0) {
                drawCentered(g2, "No students are waiting", plainFont(h * 0.04f), w / 2, (int) (h * 0.87),
                        new Color(165, 180, 252));
            } else {
                int gap = Math.max(12, (int) (w * 0.015));
                int chipH = (int) (h * 0.15);
                int chipW = Math.min((int) (w * 0.16), (int) ((w * 0.92 - gap * 4) / 5));
                int totalW = n * chipW + (n - 1) * gap;
                int startX = (w - totalW) / 2;
                int chipY = (int) (h * 0.78);

                for (int i = 0; i < n; i++) {
                    QueueEntry entry = nextEntries.get(i);
                    int x = startX + i * (chipW + gap);
                    g2.setColor(new Color(255, 255, 255, 30));
                    g2.fillRoundRect(x, chipY, chipW, chipH, 18, 18);
                    g2.setColor(new Color(255, 255, 255, 80));
                    g2.drawRoundRect(x, chipY, chipW, chipH, 18, 18);

                    drawCentered(g2, entry.getTokenText(), boldFont(chipH * 0.36f), x + chipW / 2,
                            chipY + (int) (chipH * 0.5), Color.WHITE);
                    Font smallFont = plainFont(chipH * 0.2f);
                    g2.setFont(smallFont);
                    String shortName = truncate(entry.getName(), g2.getFontMetrics(), chipW - 16);
                    drawCentered(g2, shortName, smallFont, x + chipW / 2, chipY + (int) (chipH * 0.82),
                            new Color(199, 210, 254));
                }
            }

            // Footer
            drawCentered(g2, waitingCount + " waiting", plainFont(h * 0.03f), w / 2, (int) (h * 0.97),
                    new Color(129, 140, 248));
            g2.dispose();
        }

        private static Font boldFont(float size) {
            return new Font(FONT_NAME, Font.BOLD, Math.max(10, Math.round(size)));
        }

        private static Font plainFont(float size) {
            return new Font(FONT_NAME, Font.PLAIN, Math.max(10, Math.round(size)));
        }

        /** Draws text horizontally centred on centerX, with its baseline at baselineY. */
        private static void drawCentered(Graphics2D g2, String text, Font font, int centerX,
                                         int baselineY, Color color) {
            g2.setFont(font);
            g2.setColor(color);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, centerX - fm.stringWidth(text) / 2, baselineY);
        }
    }

    /** A simple horizontal bar chart. Call setData(...) to change what it shows. */
    private static class BarChartPanel extends JPanel {
        private String[] labels = new String[0];
        private int[] values = new int[0];
        private Color[] colors = new Color[]{COLOR_BLUE};

        BarChartPanel() {
            setOpaque(false);
            setPreferredSize(new Dimension(300, 180));
        }

        void setData(String[] labels, int[] values, Color[] colors) {
            this.labels = labels.clone();
            this.values = values.clone();
            this.colors = colors.clone();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            int n = labels.length;
            if (n == 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            double rowHeight = (double) h / n;
            int fontSize = (int) Math.max(12, Math.min(17, rowHeight * 0.3));
            Font font = new Font(FONT_NAME, Font.PLAIN, fontSize);
            Font boldFont = new Font(FONT_NAME, Font.BOLD, fontSize);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();

            int labelWidth = 0;
            int maxValue = 1;
            for (int i = 0; i < n; i++) {
                labelWidth = Math.max(labelWidth, fm.stringWidth(labels[i]));
                maxValue = Math.max(maxValue, values[i]);
            }
            labelWidth += 18;
            int valueWidth = 44;
            int trackWidth = Math.max(20, w - labelWidth - valueWidth);
            int barHeight = (int) Math.max(10, Math.min(34, rowHeight * 0.5));

            for (int i = 0; i < n; i++) {
                int rowTop = (int) (i * rowHeight);
                int barY = rowTop + (int) ((rowHeight - barHeight) / 2);
                int textY = barY + (barHeight + fm.getAscent() - fm.getDescent()) / 2;

                g2.setFont(font);
                g2.setColor(COLOR_TEXT);
                g2.drawString(labels[i], 0, textY);

                g2.setColor(COLOR_BORDER);
                g2.fillRoundRect(labelWidth, barY, trackWidth, barHeight, 10, 10);

                int barWidth = values[i] * trackWidth / maxValue;
                if (values[i] > 0) {
                    barWidth = Math.max(barWidth, 12);
                    g2.setColor(colors[i % colors.length]);
                    g2.fillRoundRect(labelWidth, barY, barWidth, barHeight, 10, 10);
                }

                g2.setFont(boldFont);
                g2.setColor(COLOR_NAVY);
                g2.drawString(String.valueOf(values[i]), labelWidth + trackWidth + 12, textY);
            }
            g2.dispose();
        }
    }

    /** Shortens text with "..." so that it fits into maxWidth pixels. */
    private static String truncate(String text, FontMetrics fm, int maxWidth) {
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        String result = text;
        while (result.length() > 1 && fm.stringWidth(result + "...") > maxWidth) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "...";
    }

    // ---------------------------------------------------------------------
    // 8e. Table cell renderer and the reusable records table (search + filters)
    // ---------------------------------------------------------------------

    /** Draws table cells: striped rows, aligned columns, coloured status and bold TOTAL rows. */
    private static class StyledRenderer extends DefaultTableCellRenderer {
        private final int statusColumn;
        private final int boldColumn;
        private final Set<Integer> leftColumns;

        StyledRenderer(int statusColumn, int boldColumn, Set<Integer> leftColumns) {
            this.statusColumn = statusColumn;
            this.boldColumn = boldColumn;
            this.leftColumns = leftColumns;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);

            label.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
            label.setHorizontalAlignment(leftColumns.contains(column) ? SwingConstants.LEFT : SwingConstants.CENTER);
            label.setFont(FONT_TEXT);

            if (!isSelected) {
                label.setBackground(row % 2 == 0 ? Color.WHITE : new Color(248, 250, 252));
                label.setForeground(COLOR_TEXT);
            }

            if (column == boldColumn) {
                label.setFont(FONT_BOLD);
            }

            // Summary row at the bottom of the report tables
            boolean totalRow = "TOTAL".equals(String.valueOf(table.getValueAt(row, 0)));
            if (totalRow) {
                label.setFont(FONT_BOLD);
                if (!isSelected) {
                    label.setBackground(new Color(226, 232, 240));
                }
            }

            // Status cells get a colour that matches the status.
            if (column == statusColumn && !totalRow) {
                label.setFont(FONT_BOLD);
                if (!isSelected) {
                    String text = String.valueOf(value);
                    if (text.equals(Status.WAITING.getLabel())) {
                        label.setBackground(new Color(254, 243, 199));
                        label.setForeground(new Color(146, 64, 14));
                    } else if (text.equals(Status.SERVING.getLabel())) {
                        label.setBackground(new Color(219, 234, 254));
                        label.setForeground(new Color(30, 64, 175));
                    } else if (text.equals(Status.COMPLETED.getLabel())) {
                        label.setBackground(new Color(220, 252, 231));
                        label.setForeground(new Color(22, 101, 52));
                    }
                }
            }
            return label;
        }
    }

    /**
     * A reusable view of the records: a toolbar (search box, Search button, department filter,
     * optional status filter, Clear button), the table, and a small "Showing X of Y" line.
     * It is used on both the Manage Queue page (active students only) and the Students page (all).
     */
    private class RecordsView extends JPanel {
        private final boolean activeOnly;
        private final DefaultTableModel model;
        private final JTable table;
        private final JTextField searchField = new JTextField();
        private final JComboBox<String> departmentFilter = new JComboBox<>();
        private final JComboBox<String> statusFilter;   // null when activeOnly
        private final JLabel countLabel = new JLabel(" ");
        private final List<QueueEntry> visible = new ArrayList<>();

        RecordsView(boolean activeOnly) {
            super(new BorderLayout(0, 12));
            this.activeOnly = activeOnly;
            setOpaque(false);

            // ----- Table -----
            model = new DefaultTableModel(
                    new String[]{"Token", "Student Name", "Department", "Status", "Created"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            table = createStyledTable(model, 3, 0, 1, 2);
            table.getColumnModel().getColumn(0).setPreferredWidth(90);
            table.getColumnModel().getColumn(1).setPreferredWidth(260);
            table.getColumnModel().getColumn(2).setPreferredWidth(170);
            table.getColumnModel().getColumn(3).setPreferredWidth(120);
            table.getColumnModel().getColumn(4).setPreferredWidth(180);

            // ----- Toolbar controls -----
            styleTextField(searchField);
            searchField.setPreferredSize(new Dimension(200, 42));
            searchField.setToolTipText("Search by token, name, department or status");
            searchField.addActionListener(e -> searchRecords());

            departmentFilter.setFont(FONT_TEXT);
            departmentFilter.setPreferredSize(new Dimension(200, 42));
            departmentFilter.addItem(ALL_DEPARTMENTS);
            for (String department : DEPARTMENTS) {
                departmentFilter.addItem(department);
            }
            departmentFilter.addActionListener(e -> refresh());

            if (activeOnly) {
                statusFilter = null;
            } else {
                statusFilter = new JComboBox<>();
                statusFilter.setFont(FONT_TEXT);
                statusFilter.setPreferredSize(new Dimension(170, 42));
                statusFilter.addItem(ALL_STATUSES);
                for (Status status : Status.values()) {
                    statusFilter.addItem(status.getLabel());
                }
                statusFilter.addActionListener(e -> refresh());
            }

            RoundedButton searchButton = new RoundedButton("Search", COLOR_NAVY, IconType.SEARCH);
            searchButton.addActionListener(e -> searchRecords());
            RoundedButton clearButton = new RoundedButton("Clear", COLOR_GREY, IconType.CLOSE);
            clearButton.addActionListener(e -> {
                clearFilters();
                refresh();
                setStatus("Filters cleared. Showing all records.");
            });

            JPanel toolbar = new JPanel(new GridBagLayout());
            toolbar.setOpaque(false);
            GridBagConstraints gc = new GridBagConstraints();
            gc.gridy = 0;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.insets = new Insets(0, 0, 0, 10);
            gc.gridx = 0;
            gc.weightx = 1.0;
            toolbar.add(searchField, gc);
            gc.weightx = 0;
            gc.gridx = 1;
            toolbar.add(searchButton, gc);
            gc.gridx = 2;
            toolbar.add(departmentFilter, gc);
            gc.gridx = 3;
            if (statusFilter != null) {
                toolbar.add(statusFilter, gc);
                gc.gridx = 4;
            }
            gc.insets = new Insets(0, 0, 0, 0);
            toolbar.add(clearButton, gc);

            countLabel.setFont(FONT_SMALL);
            countLabel.setForeground(COLOR_GREY);

            add(toolbar, BorderLayout.NORTH);
            add(wrapInScroll(table), BorderLayout.CENTER);
            add(countLabel, BorderLayout.SOUTH);
        }

        /** Rebuilds the table rows from the data, applying the search text and the filters. */
        void refresh() {
            QueueEntry previouslySelected = getSelected();
            model.setRowCount(0);
            visible.clear();

            String text = searchField.getText().trim();
            String department = (String) departmentFilter.getSelectedItem();
            String status = statusFilter == null ? ALL_STATUSES : (String) statusFilter.getSelectedItem();
            int totalAvailable = 0;

            for (QueueEntry entry : entries) {
                if (activeOnly && entry.getStatus() == Status.COMPLETED) {
                    continue;
                }
                totalAvailable++;
                if (!ALL_DEPARTMENTS.equals(department) && !entry.getDepartment().equals(department)) {
                    continue;
                }
                if (!ALL_STATUSES.equals(status) && !entry.getStatus().getLabel().equals(status)) {
                    continue;
                }
                if (!entry.matches(text)) {
                    continue;
                }
                visible.add(entry);
                model.addRow(new Object[]{
                        entry.getTokenText(),
                        entry.getName(),
                        entry.getDepartment(),
                        entry.getStatus().getLabel(),
                        entry.getCreated()
                });
            }

            if (totalAvailable == 0) {
                countLabel.setText(activeOnly
                        ? "Nobody is in the queue. Generate a token to get started."
                        : "No students yet. Generate a token on the Manage Queue page.");
            } else if (visible.isEmpty()) {
                countLabel.setText("No records match your search or filters.");
            } else {
                countLabel.setText("Showing " + visible.size() + " of " + totalAvailable + " record(s)");
            }

            if (previouslySelected != null) {
                select(previouslySelected);
            }
        }

        /** Search button / Enter key. */
        private void searchRecords() {
            refresh();
            String text = searchField.getText().trim();
            if (text.isEmpty()) {
                setStatus("Showing " + visible.size() + " record(s).");
            } else {
                setStatus("Found " + visible.size() + " record(s) matching \"" + text + "\".");
            }
        }

        /** Empties the search box and resets the filters (call refresh() afterwards). */
        void clearFilters() {
            searchField.setText("");
            departmentFilter.setSelectedIndex(0);
            if (statusFilter != null) {
                statusFilter.setSelectedIndex(0);
            }
        }

        /** The entry for the highlighted row, or null if no row is selected. */
        QueueEntry getSelected() {
            int row = table.getSelectedRow();
            if (row >= 0 && row < visible.size()) {
                return visible.get(row);
            }
            return null;
        }

        /** Highlights the given entry (if it is visible) and scrolls to it. */
        void select(QueueEntry entry) {
            int index = visible.indexOf(entry);
            if (index >= 0) {
                table.setRowSelectionInterval(index, index);
                table.scrollRectToVisible(table.getCellRect(index, 0, true));
            }
        }
    }
}
