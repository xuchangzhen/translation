package com.linguabridge.memory;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.animation.AnimatorSet;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;

public final class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final String AI_LOG_TAG = "LinguaBridgeAI";
    private static final String AI_RUN_PREFS = "ai_enrichment_runs";
    private static final int CARD_CORNER_RADIUS_DP = 24;
    private static final int STAT_CARD_CORNER_RADIUS_DP = 22;
    private static final int INSET_SURFACE_CORNER_RADIUS_DP = 14;
    private static final int PILL_CORNER_RADIUS_DP = 12;

    private int BLUE;
    private int CORAL;
    private int LILAC;
    private int MINT;
    private int INK;
    private int MUTED;
    private int SURFACE;
    private int CARD;
    private int CARD_RAISED;
    private int LINE;
    private boolean darkMode;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private MemoryDb db;
    private WordbookImportUi wordbookImport;
    private AiConfigStore aiConfigStore;
    private long libraryWordbookId = 0, reviewWordbookId = 0;
    private String libraryWordbookName = "全部";
    private int libraryOffset = 0;
    private SecureStore secureStore;
    private FrameLayout content;
    private FrameLayout rootHost;
    private LinearLayout navigation;
    private View rootLayout;
    private TextToSpeech textToSpeech;
    private String currentPage = "home";
    private String syncMessage = "等待自动接收";
    private boolean themeTransitioning;
    private TextView homeSyncMessageView;
    private TextView connectSyncMessageView;
    private boolean reviewPracticeMode;
    private long reviewSessionStartedAt;
    private boolean reviewAdvancing;
    private boolean reviewCardSwitching;
    private boolean suppressNextPageAnimation;
    /** The wordbook that was just pinned/unpinned, so its new position can arrive visibly. */
    private long managementAnimatedWordbookId = -1;
    private boolean managementAnimatedAsPinned;
    private int reviewSessionTotal;
    private int reviewProgressAnimationStart;
    private boolean animateReviewProgress;
    private ReviewSwipeStage reviewSwipeStage;
    /** Cards encountered in this session. Keeping this small, growing history makes swipe-back predictable. */
    private final List<MemoryCard> reviewCards = new ArrayList<>();
    private final Map<Long, String> reviewRatings = new HashMap<>();
    private final Map<Long, ReviewBaseline> reviewBaselines = new HashMap<>();
    private final Map<Dialog, LinearLayout> panelShells = new HashMap<>();
    private int reviewCardIndex = -1;
    private String reviewSessionId = "";
    private long revealedReviewCardId = -1;
    private boolean aiTaskRunning;
    private long aiTaskWordbookId;
    private int aiTaskRequested;
    private int aiTaskProcessed;
    private int aiTaskCompleted;
    private TextView aiStatusView;
    private TextView aiProgressView;
    private ProgressBar aiProgressBar;
    private TextView aiTaskProgressView;
    private ProgressBar aiTaskProgressBar;
    private TextView aiRunStateView;
    private TextView aiCompletionSummaryView;
    private TextView aiCompletedMetricView;
    private TextView aiProcessingMetricView;
    private TextView aiPendingMetricView;
    private Button aiCompletionButtonView;
    private Dialog aiProgressDialog;
    private long aiProgressDialogWordbookId = -1;
    private TextView aiDialogPercentView;
    private TextView aiDialogSummaryView;
    private TextView aiDialogDetailView;
    private ProgressBar aiDialogProgressBar;
    private final Map<Long, AiEnrichmentStats> aiStatsCache = new HashMap<>();

    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra("message");
            if (message != null && !message.trim().isEmpty()) syncMessage = message;
            // The receive loop broadcasts a connected heartbeat after every long poll.
            // Updating the visible status text keeps the screen stable instead of rebuilding it.
            updateSyncMessageViews();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyPalette();
        db = new MemoryDb(this);
        wordbookImport = new WordbookImportUi(this, (id, name) -> {
            libraryWordbookId = id; libraryWordbookName = name; libraryOffset = 0; showLibrary(); runPhoneticBackfill();
        });
        aiConfigStore = new AiConfigStore(this);
        secureStore = new SecureStore(this);
        textToSpeech = new TextToSpeech(this, this);
        ReviewNotifications.createChannels(this);
        requestNotificationPermission();
        rootHost = new FrameLayout(this);
        setContentView(rootHost);
        buildShell();
        registerSyncReceiver();
        runPhoneticBackfill();
        AppUpdateManager.checkAsync(this, false);
        boolean pairingIntent = handlePairingIntent(getIntent());
        if (!pairingIntent && secureStore.load() != null) CloudSyncService.start(this);
        if (pairingIntent) showConnect();
        else if (getIntent().getBooleanExtra("openReview", false)) beginReview(0);
        else showHome();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (handlePairingIntent(intent)) showConnect();
        else if (intent.getBooleanExtra("openReview", false)) beginReview(0);
    }

    private boolean handlePairingIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return false;
        if (!"linguabridge-memory".equals(intent.getData().getScheme())) return false;
        syncMessage = "正在验证桌面端创建的加密设备…";
        final SyncConfig pairing;
        try {
            pairing = SyncConfig.fromPairingUri(intent.getDataString());
        } catch (Exception error) {
            syncMessage = "二维码无效：" + error.getMessage();
            Toast.makeText(this, syncMessage, Toast.LENGTH_LONG).show();
            return true;
        }
        io.execute(() -> {
            try {
                CloudApi.test(pairing);
                secureStore.save(pairing);
                runOnUiThread(() -> {
                    CloudSyncService.start(this);
                    syncMessage = "配置完成，正在自动接收翻译内容";
                    showConnect();
                    Toast.makeText(this, "连接成功，无需再手动配置", Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    syncMessage = "连接失败：" + error.getMessage();
                    showConnect();
                    Toast.makeText(this, syncMessage, Toast.LENGTH_LONG).show();
                });
            }
        });
        return true;
    }

    @Override
    protected void onDestroy() {
        wordbookImport.destroy();
        unregisterReceiver(syncReceiver);
        io.shutdownNow();
        if (textToSpeech != null) textToSpeech.shutdown();
        super.onDestroy();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) textToSpeech.setLanguage(Locale.US);
    }

    @SuppressWarnings("deprecation")
    private void applyPalette() {
        String preference = getSharedPreferences("appearance", MODE_PRIVATE)
                .getString("theme", "system");
        boolean systemDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        darkMode = "dark".equals(preference) || ("system".equals(preference) && systemDark);
        BLUE = darkMode ? Color.rgb(174, 124, 255) : Color.rgb(126, 76, 181);
        CORAL = darkMode ? Color.rgb(255, 126, 72) : Color.rgb(231, 101, 56);
        LILAC = darkMode ? Color.rgb(207, 181, 255) : Color.rgb(112, 72, 156);
        MINT = darkMode ? Color.rgb(125, 238, 174) : Color.rgb(31, 142, 91);
        INK = darkMode ? Color.rgb(247, 243, 255) : Color.rgb(43, 36, 51);
        MUTED = darkMode ? Color.rgb(169, 160, 184) : Color.rgb(112, 103, 122);
        SURFACE = darkMode ? Color.rgb(9, 8, 14) : Color.rgb(247, 244, 250);
        CARD = darkMode ? Color.rgb(25, 21, 35) : Color.rgb(255, 255, 255);
        CARD_RAISED = darkMode ? Color.rgb(34, 28, 46) : Color.rgb(246, 241, 249);
        LINE = darkMode ? Color.rgb(48, 41, 61) : Color.rgb(235, 228, 239);
        getWindow().setStatusBarColor(SURFACE);
        getWindow().setNavigationBarColor(darkMode ? Color.rgb(13, 11, 19) : Color.rgb(244, 239, 248));
        int flags = darkMode ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        rootLayout = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(gradient(new int[]{darkMode ? Color.rgb(19, 13, 29) : Color.rgb(253, 250, 255), SURFACE}, 0));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.statusBars()).top
                    : legacyStatusBarInset(insets);
            view.setPadding(0, top, 0, 0);
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(18), dp(20), dp(12));
        ImageView mark = new ImageView(this);
        mark.setImageResource(darkMode
                ? com.linguabridge.memory.R.drawable.linguabridge_memory_header_dark
                : com.linguabridge.memory.R.drawable.linguabridge_memory_header_light);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mark.setAdjustViewBounds(true);
        mark.setContentDescription("加密记忆同步");
        header.addView(mark, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout titles = vertical(2);
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(label("单词记忆", 20, INK, Typeface.BOLD));
        titles.addView(label("离线复习 · 加密自动接收", 11, MUTED, Typeface.NORMAL));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button theme = linkButton("");
        theme.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0,
                darkMode ? com.linguabridge.memory.R.drawable.ic_theme_sun
                        : com.linguabridge.memory.R.drawable.ic_theme_moon,
                0,
                0
        );
        theme.setCompoundDrawableTintList(ColorStateList.valueOf(BLUE));
        applyFlatButtonBehavior(theme);
        theme.setContentDescription(darkMode ? "切换到浅色模式" : "切换到深色模式");
        theme.setOnClickListener(view -> transitionTheme(view, darkMode ? "light" : "dark"));
        theme.setOnLongClickListener(view -> {
            Toast.makeText(this, "已改为跟随系统外观", Toast.LENGTH_SHORT).show();
            transitionTheme(view, "system");
            return true;
        });
        header.addView(theme, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(14), dp(9), dp(14), dp(12));
        navigation.setBackground(gradient(new int[]{
                darkMode ? Color.rgb(19, 16, 27) : Color.rgb(250, 247, 252),
                darkMode ? Color.rgb(13, 11, 19) : Color.rgb(244, 239, 248)
        }, 0));
        addNav("今天", "home", com.linguabridge.memory.R.drawable.ic_nav_today, this::showHome);
        addNav("词库", "library", com.linguabridge.memory.R.drawable.ic_nav_library, this::showLibrary);
        addNav("连接桌面", "connect", com.linguabridge.memory.R.drawable.ic_nav_connect, this::showConnect);
        root.addView(navigation);
        rootHost.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private void transitionTheme(View origin, String preference) {
        if (themeTransitioning) return;
        boolean systemDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean targetDark = "dark".equals(preference) || ("system".equals(preference) && systemDark);
        getSharedPreferences("appearance", MODE_PRIVATE)
                .edit()
                .putString("theme", preference)
                .apply();
        if (targetDark == darkMode) {
            return;
        }
        themeTransitioning = true;
        String page = currentPage;
        View previousRoot = rootLayout;
        origin.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        origin.animate().rotationBy(180f).scaleX(0.82f).scaleY(0.82f).setDuration(360).start();
        applyPalette();
        buildShell();
        View nextRoot = rootLayout;
        renderCurrentPage(page);
        if (!ValueAnimator.areAnimatorsEnabled()) {
            rootHost.removeView(previousRoot);
            themeTransitioning = false;
            return;
        }
        nextRoot.setAlpha(0f);
        nextRoot.setScaleX(1.008f);
        nextRoot.setScaleY(1.008f);
        AnimatorSet blend = new AnimatorSet();
        blend.playTogether(
                ObjectAnimator.ofFloat(previousRoot, View.ALPHA, 1f, 0.48f, 0f),
                ObjectAnimator.ofFloat(previousRoot, View.SCALE_X, 1f, 0.996f),
                ObjectAnimator.ofFloat(previousRoot, View.SCALE_Y, 1f, 0.996f),
                ObjectAnimator.ofFloat(nextRoot, View.ALPHA, 0f, 0.52f, 1f),
                ObjectAnimator.ofFloat(nextRoot, View.SCALE_X, 1.008f, 1f),
                ObjectAnimator.ofFloat(nextRoot, View.SCALE_Y, 1.008f, 1f)
        );
        blend.setDuration(560);
        blend.setInterpolator(new DecelerateInterpolator());
        blend.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                rootHost.removeView(previousRoot);
                themeTransitioning = false;
            }
        });
        blend.start();
    }

    private void renderCurrentPage(String page) {
        if ("library".equals(page)) showLibrary();
        else if ("connect".equals(page)) showConnect();
        else if ("review".equals(page)) showReview();
        else showHome();
    }

    private void addNav(String title, String page, int iconResource, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(10);
        button.setAllCaps(false);
        button.setTag(page);
        button.setOnClickListener(view -> action.run());
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, iconResource, 0, 0);
        button.setCompoundDrawablePadding(dp(2));
        applyFlatButtonBehavior(button);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        navigation.addView(button, new LinearLayout.LayoutParams(0, dp(56), 1));
    }

    private void selectPage(String page) {
        currentPage = page;
        for (int index = 0; index < navigation.getChildCount(); index++) {
            Button button = (Button) navigation.getChildAt(index);
            boolean selected = page.equals(button.getTag());
            button.setTextColor(selected ? BLUE : MUTED);
            button.setCompoundDrawableTintList(ColorStateList.valueOf(selected ? BLUE : MUTED));
            button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            button.animate()
                    .scaleX(selected ? 1f : 0.97f)
                    .scaleY(selected ? 1f : 0.97f)
                    .setDuration(180)
                    .start();
            button.setBackground(rounded(Color.TRANSPARENT, 14));
        }
    }

    private void showHome() {
        selectPage("home");
        long now = System.currentTimeMillis();
        MemoryDb.Stats stats = db.stats(now);
        LinearLayout body = pageBody();
        body.addView(eyebrow("TODAY"));
        body.addView(title("今天要记住什么？", 29));
        body.addView(subtitle("桌面翻译器会自动送来新内容；自定义词库可加密同步到已连接设备。"));

        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setPadding(0, dp(18), 0, dp(18));
        statsRow.addView(statCard("待复习", stats.due, "现在可回忆"), weighted());
        statsRow.addView(space(dp(10)));
        statsRow.addView(statCard("新内容", stats.fresh, "尚未首轮"), weighted());
        body.addView(statsRow);

        LinearLayout rhythm = card();
        LinearLayout rhythmTitle = new LinearLayout(this);
        rhythmTitle.setGravity(Gravity.CENTER_VERTICAL);
        rhythmTitle.addView(label("今日节奏", 12, INK, Typeface.BOLD), weighted());
        rhythmTitle.addView(label(stats.todayReviews + " 次回忆", 11, LILAC, Typeface.BOLD));
        rhythm.addView(rhythmTitle);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(Math.max(1, stats.todayReviews + stats.due));
        progress.setProgress(stats.todayReviews);
        progress.setProgressTintList(ColorStateList.valueOf(BLUE));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(
                darkMode ? Color.rgb(47, 39, 60) : Color.rgb(225, 216, 232)
        ));
        LinearLayout.LayoutParams progressParams = fullHeight(5);
        progressParams.topMargin = dp(12);
        rhythm.addView(progress, progressParams);
        rhythm.addView(label(
                stats.due == 0 ? "今日计划已完成；也可以继续巩固已学内容。" : "每次短暂回忆，都在把知识放进长期记忆。",
                10,
                MUTED,
                Typeface.NORMAL
        ));
        LinearLayout.LayoutParams rhythmParams = fullWrap();
        rhythmParams.bottomMargin = dp(16);
        body.addView(rhythm, rhythmParams);

        Button start = primaryButton(stats.due > 0 ? "开始复习 · " + stats.due : "继续巩固已学内容");
        start.setOnClickListener(view -> beginReview(0));
        body.addView(start, fullHeight(52));

        SyncConfig syncConfig = secureStore.load();
        LinearLayout sync = card();
        sync.addView(statusHeader(
                "自动接收",
                syncConfig == null ? "未连接" : "已加密连接",
                syncConfig == null ? MUTED : MINT
        ));
        TextView syncState = label(
                syncConfig == null ? "连接桌面后，翻译内容会自动来到这里" : syncMessage,
                11,
                syncConfig == null ? MUTED : BLUE,
                Typeface.NORMAL
        );
        syncState.setPadding(0, dp(9), 0, 0);
        homeSyncMessageView = syncState;
        connectSyncMessageView = null;
        sync.addView(syncState);
        TextView detail = label(
                "端到端加密  ·  离线自动补收  ·  服务器不可读取词条",
                10,
                MUTED,
                Typeface.NORMAL
        );
        detail.setPadding(0, dp(8), 0, 0);
        sync.addView(detail);
        LinearLayout peers = vertical(0);
        peers.setPadding(0, dp(12), 0, 0);
        sync.addView(peers);
        if (syncConfig == null) {
            peers.addView(emptyPresence("连接后，这里会显示 Mac 与 Windows 的实时状态"));
        } else {
            peers.addView(emptyPresence("正在读取设备状态…"));
            loadDesktopPresence(peers, syncConfig);
        }
        LinearLayout.LayoutParams syncParams = fullWrap();
        syncParams.topMargin = dp(16);
        body.addView(sync, syncParams);
        setPage(body);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WordbookImportUi.PICK_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            wordbookImport.selected(data.getData());
        }
    }

    private void showLibrary() {
        selectPage("library");
        aiStatusView = null;
        aiProgressView = null;
        aiProgressBar = null;
        aiTaskProgressView = null;
        aiTaskProgressBar = null;
        aiCompletionButtonView = null;
        LinearLayout body = pageBody();
        long now = System.currentTimeMillis();
        MemoryDb.Stats stats = db.stats(now);
        List<Wordbook> wordbooks = db.wordbooks(now);
        body.addView(eyebrow("LIBRARY"));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL | Gravity.BOTTOM);
        heading.addView(title("我的词库", 29), weighted());
        Button aiSettingsShortcut = linkButton("AI服务");
        aiSettingsShortcut.setTextColor(LILAC);
        aiSettingsShortcut.setContentDescription("修改 AI 服务和模型");
        aiSettingsShortcut.setOnClickListener(view -> showAiSettingsPanel());
        heading.addView(aiSettingsShortcut, new LinearLayout.LayoutParams(dp(64), dp(44)));
        Button importButton = importWordbookButton();
        importButton.setOnClickListener(view -> wordbookImport.choose());
        heading.addView(importButton, new LinearLayout.LayoutParams(dp(110), dp(44)));
        body.addView(heading);
        body.addView(subtitle("每一组词都有自己的语境和复习进度。"));

        LinearLayout overview = card();
        overview.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout overviewTitle = new LinearLayout(this);
        overviewTitle.setGravity(Gravity.CENTER_VERTICAL);
        overviewTitle.addView(label("词库总览", 13, INK, Typeface.BOLD), weighted());
        overviewTitle.addView(statusPill(wordbooks.size() + " 个词库", BLUE));
        overview.addView(overviewTitle);
        TextView overviewHint = label("上下滑动选择词库；每项都有独立的学习进度。", 10, MUTED, Typeface.NORMAL);
        overviewHint.setPadding(0, dp(6), 0, dp(14));
        overview.addView(overviewHint);
        ScrollView wordbookScroll = new ScrollView(this);
        wordbookScroll.setFillViewport(true);
        wordbookScroll.setNestedScrollingEnabled(true);
        wordbookScroll.setVerticalScrollBarEnabled(true);
        LinearLayout wordbookList = vertical(0);
        wordbookScroll.addView(wordbookList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        wordbookList.addView(wordbookSelector(
                "全部", "所有词库的统一视图", stats.total, stats.due, stats.fresh,
                libraryWordbookId == 0, "全", BLUE,
                () -> selectLibraryWordbook(0, "全部")
        ));
        for (Wordbook book : wordbooks) {
            LinearLayout.LayoutParams selectorParams = fullWrap();
            selectorParams.topMargin = dp(9);
            View selector = wordbookSelector(
                    book.name,
                    book.id == 1 ? "桌面翻译自动接收" : (book.pinnedAt > 0 ? "自定义词库 · 已置顶" : "自定义词库 · 本地保存"),
                    book.total, book.due, book.fresh,
                    libraryWordbookId == book.id,
                    book.id == 1 ? "桌" : "词",
                    book.id == 1 ? CORAL : LILAC,
                    () -> selectLibraryWordbook(book.id, book.name)
            );
            // Only custom books opt into the horizontal action row. The shared ScrollView keeps
            // vertical ownership until a clearly horizontal left drag is detected.
            View wordbookItem = book.id > 1 ? new WordbookSwipeActionRow(book, selector) : selector;
            wordbookList.addView(wordbookItem, selectorParams);
            if (book.id == managementAnimatedWordbookId) animateManagedWordbookArrival(wordbookItem, managementAnimatedAsPinned);
        }
        managementAnimatedWordbookId = -1;
        overview.addView(wordbookScroll, fullHeight(248));

        LinearLayout.LayoutParams overviewParams = fullWrap();
        overviewParams.topMargin = dp(18);
        body.addView(overview, overviewParams);

        LinearLayout selected = card();
        selected.setPadding(dp(18), dp(16), dp(18), dp(17));
        LinearLayout selectedHeading = new LinearLayout(this);
        selectedHeading.setGravity(Gravity.CENTER_VERTICAL);
        selectedHeading.addView(label("正在浏览", 10, BLUE, Typeface.BOLD), weighted());
        int selectedAccent = libraryWordbookId == 1 ? CORAL : libraryWordbookId > 1 ? LILAC : BLUE;
        selectedHeading.addView(statusPill(currentLibraryScope(), selectedAccent));
        selected.addView(selectedHeading);
        TextView selectedName = title(libraryWordbookName, 22);
        selectedName.setPadding(0, dp(5), 0, dp(1));
        selected.addView(selectedName);
        selected.addView(librarySummary(libraryWordbookId == 0 ? stats.total : selectedWordbookTotal(wordbooks),
                libraryWordbookId == 0 ? stats.due : selectedWordbookDue(wordbooks),
                libraryWordbookId == 0 ? stats.fresh : selectedWordbookFresh(wordbooks)));
        if (libraryWordbookId > 0) {
            LinearLayout aiStatus = aiEnrichmentStatus();
            LinearLayout.LayoutParams aiStatusParams = fullWrap();
            aiStatusParams.topMargin = dp(15);
            selected.addView(aiStatus, aiStatusParams);
            Button complete = aiCompletionButton();
            complete.setOnClickListener(view -> {
                if (aiTaskRunning && aiTaskWordbookId == libraryWordbookId) {
                    showAiProgressPanel(libraryWordbookId, libraryWordbookName);
                } else {
                    showAiCompletionPicker(selectedWordbookMode(wordbooks));
                }
            });
            LinearLayout.LayoutParams completeParams = fullHeight(46);
            completeParams.topMargin = dp(10);
            selected.addView(complete, completeParams);
            Button aiSettings = paginationButton("⚙  修改 AI 服务与模型");
            aiSettings.setContentDescription("修改 AI 服务地址、API Key 或补全模型");
            aiSettings.setOnClickListener(view -> showAiSettingsPanel());
            LinearLayout.LayoutParams settingsParams = fullHeight(40);
            settingsParams.topMargin = dp(7);
            selected.addView(aiSettings, settingsParams);
            loadAiStatsAsync(libraryWordbookId);
        }
        Button study = primaryButton("开始学习 / 开始复习");
        study.setOnClickListener(view -> beginReview(libraryWordbookId));
        LinearLayout.LayoutParams studyParams = fullHeight(50);
        studyParams.topMargin = dp(16);
        selected.addView(study, studyParams);
        LinearLayout.LayoutParams selectedParams = fullWrap();
        selectedParams.topMargin = dp(14);
        body.addView(selected, selectedParams);

        if (libraryWordbookId > 1) {
            Button sync = syncWordbookButton();
            sync.setOnClickListener(view -> uploadSelectedWordbook(sync));
            LinearLayout.LayoutParams syncParams = fullHeight(46);
            syncParams.topMargin = dp(10);
            body.addView(sync, syncParams);
        }
        List<MemoryCard> cards = db.recent(61, libraryWordbookId, libraryOffset);
        TextView wordSection = sectionLabel(cards.isEmpty() ? "等待第一条词汇" : "词条 · 最近加入");
        wordSection.setPadding(0, dp(20), 0, dp(8));
        body.addView(wordSection);
        if (cards.isEmpty()) body.addView(libraryEmptyState());
        for (MemoryCard memoryCard : cards.subList(0, Math.min(60, cards.size()))) body.addView(libraryRow(memoryCard));
        if (libraryOffset > 0) {
            Button previous = paginationButton("← 上一页");
            previous.setOnClickListener(view -> { libraryOffset = Math.max(0, libraryOffset - 60); showLibrary(); });
            LinearLayout.LayoutParams previousParams = fullHeight(44);
            previousParams.topMargin = dp(12);
            body.addView(previous, previousParams);
        }
        if (cards.size() > 60) {
            Button next = paginationButton("下一页 →");
            next.setOnClickListener(view -> { libraryOffset += 60; showLibrary(); });
            LinearLayout.LayoutParams nextParams = fullHeight(44);
            nextParams.topMargin = dp(8);
            body.addView(next, nextParams);
        }
        setPage(body);
    }

    private void selectLibraryWordbook(long id, String name) {
        libraryWordbookId = id;
        libraryWordbookName = name;
        libraryOffset = 0;
        showLibrary();
    }

    /** Re-sort management changes after the drawer closes, without replaying page entrance animation. */
    private void refreshLibraryAfterManagementChange(long wordbookId, boolean pinned) {
        managementAnimatedWordbookId = wordbookId;
        managementAnimatedAsPinned = pinned;
        suppressNextPageAnimation = true;
        content.post(this::showLibrary);
    }

    /** Gives the moved row a short arrival motion at its newly sorted position. */
    private void animateManagedWordbookArrival(View item, boolean pinned) {
        item.setAlpha(0f);
        item.setScaleX(0.97f);
        item.setScaleY(0.97f);
        item.setTranslationY(dp(pinned ? 18 : -12));
        item.post(() -> item.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(280)
                .setInterpolator(new DecelerateInterpolator())
                .start());
    }

    private String currentLibraryScope() {
        if (libraryWordbookId == 0) return "全部词库";
        return libraryWordbookId == 1 ? "桌面同步" : "自定义词库";
    }

    private int selectedWordbookTotal(List<Wordbook> wordbooks) {
        for (Wordbook book : wordbooks) if (book.id == libraryWordbookId) return book.total;
        return 0;
    }

    private int selectedWordbookDue(List<Wordbook> wordbooks) {
        for (Wordbook book : wordbooks) if (book.id == libraryWordbookId) return book.due;
        return 0;
    }

    private int selectedWordbookFresh(List<Wordbook> wordbooks) {
        for (Wordbook book : wordbooks) if (book.id == libraryWordbookId) return book.fresh;
        return 0;
    }

    private String selectedWordbookMode(List<Wordbook> wordbooks) {
        for (Wordbook book : wordbooks) if (book.id == libraryWordbookId) return book.contentMode;
        return "general";
    }

    private Button importWordbookButton() {
        Button button = new Button(this);
        button.setText("＋ 导入词库");
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(gradient(new int[]{BLUE, LILAC}, 14));
        button.setContentDescription("导入词库文件");
        applyFlatButtonBehavior(button);
        return button;
    }

    private Button syncWordbookButton() {
        Button button = new Button(this);
        button.setText("↗  同步到已连接的设备");
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTextColor(BLUE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(rounded(withAlpha(BLUE, darkMode ? 38 : 20), 14,
                withAlpha(BLUE, darkMode ? 100 : 72)));
        button.setContentDescription("将当前词库加密同步到已连接的设备");
        applyFlatButtonBehavior(button);
        return button;
    }

    private Button aiCompletionButton() {
        Button button = new Button(this);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTextColor(LILAC);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(rounded(withAlpha(LILAC, darkMode ? 38 : 18), 14, withAlpha(LILAC, darkMode ? 110 : 76)));
        button.setContentDescription("为当前词库补全 AI 学习内容");
        applyFlatButtonBehavior(button);
        aiCompletionButtonView = button;
        updateAiCompletionButton();
        return button;
    }

    private void updateAiCompletionButton() {
        // This is also called while the button is being constructed, before it is attached.
        // Requiring attachment left its otherwise styled slot blank on the first library render.
        if (aiCompletionButtonView == null) return;
        boolean thisWordbookIsRunning = aiTaskRunning && aiTaskWordbookId == libraryWordbookId;
        aiCompletionButtonView.setText(thisWordbookIsRunning ? "◉  查看 AI 补全进度" : "✦  AI 补全");
        aiCompletionButtonView.setEnabled(!aiTaskRunning || thisWordbookIsRunning);
        aiCompletionButtonView.setContentDescription(thisWordbookIsRunning
                ? "查看当前词库的 AI 补全进度" : "为当前词库补全 AI 学习内容");
    }

    private LinearLayout aiEnrichmentStatus() {
        LinearLayout status = vertical(0);
        applyInsetSurfaceStyle(status, 16);
        status.setPadding(dp(13), dp(11), dp(13), dp(11));
        status.setClickable(true);
        status.setOnClickListener(view -> showAiProgressPanel(libraryWordbookId, libraryWordbookName));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label("AI 补全进度", 11, INK, Typeface.BOLD), weighted());
        TextView runState = label("正在统计…", 10, LILAC, Typeface.BOLD);
        aiRunStateView = runState;
        heading.addView(runState);
        status.addView(heading);

        LinearLayout totalRow = new LinearLayout(this);
        totalRow.setGravity(Gravity.BOTTOM | Gravity.CENTER_VERTICAL);
        TextView value = label("0%", 22, LILAC, Typeface.BOLD);
        value.setIncludeFontPadding(false);
        aiStatusView = value;
        totalRow.addView(value);
        TextView totalCaption = label("  已补全 0 / 0", 10, MUTED, Typeface.NORMAL);
        totalCaption.setPadding(0, 0, 0, dp(2));
        aiCompletionSummaryView = totalCaption;
        totalRow.addView(totalCaption);
        status.addView(totalRow);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1);
        progress.setProgress(0);
        progress.setProgressTintList(ColorStateList.valueOf(LILAC));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(LILAC, darkMode ? 44 : 28)));
        LinearLayout.LayoutParams progressParams = fullHeight(8);
        progressParams.topMargin = dp(9);
        status.addView(progress, progressParams);
        aiProgressBar = progress;

        TextView taskProgress = label("", 9, LILAC, Typeface.BOLD);
        taskProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams taskTextParams = fullWrap();
        taskTextParams.topMargin = dp(8);
        status.addView(taskProgress, taskTextParams);
        aiTaskProgressView = taskProgress;

        ProgressBar taskBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        taskBar.setMax(1);
        taskBar.setProgress(0);
        taskBar.setProgressTintList(ColorStateList.valueOf(LILAC));
        taskBar.setIndeterminateTintList(ColorStateList.valueOf(LILAC));
        taskBar.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(LILAC, darkMode ? 44 : 28)));
        taskBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams taskBarParams = fullHeight(5);
        taskBarParams.topMargin = dp(4);
        status.addView(taskBar, taskBarParams);
        aiTaskProgressBar = taskBar;

        LinearLayout metrics = new LinearLayout(this);
        metrics.setPadding(0, dp(10), 0, 0);
        TextView completed = label("0", 15, MINT, Typeface.BOLD);
        TextView processing = label("0", 15, LILAC, Typeface.BOLD);
        TextView pending = label("0", 15, INK, Typeface.BOLD);
        aiCompletedMetricView = completed;
        aiProcessingMetricView = processing;
        aiPendingMetricView = pending;
        metrics.addView(aiProgressMetric("已完成", completed, MINT), weighted());
        metrics.addView(aiProgressMetric("处理中", processing, LILAC), weighted());
        metrics.addView(aiProgressMetric("待补全", pending, MUTED), weighted());
        status.addView(metrics);

        TextView detail = label("轻触此卡片可查看补全详情。", 9, MUTED, Typeface.NORMAL);
        detail.setPadding(0, dp(8), 0, 0);
        aiProgressView = detail;
        status.addView(detail);
        return status;
    }

    private LinearLayout aiProgressMetric(String caption, TextView value, int captionColor) {
        LinearLayout column = vertical(0);
        column.addView(value);
        column.addView(label(caption, 9, captionColor, Typeface.NORMAL));
        return column;
    }

    private void loadAiStatsAsync(long wordbookId) {
        if (wordbookId <= 0) return;
        io.execute(() -> {
            try (MemoryDb local = new MemoryDb(getApplicationContext())) {
                AiEnrichmentStats stats = local.aiStats(wordbookId);
                runOnUiThread(() -> updateAiStatusViews(wordbookId, stats, aiTaskProcessed, aiTaskRequested));
            }
        });
    }

    /** Updates the progress views only; it never rebuilds the scroll page during a batch. */
    private void updateAiStatusViews(long wordbookId, AiEnrichmentStats stats, int processed, int requested) {
        if (stats == null) return;
        aiStatsCache.put(wordbookId, stats);
        updateAiProgressDialog(wordbookId, stats, processed, requested);
        if (libraryWordbookId != wordbookId || aiStatusView == null || !aiStatusView.isAttachedToWindow()) return;
        boolean running = aiTaskRunning && aiTaskWordbookId == wordbookId;
        int percent = stats.total == 0 ? 0 : Math.round(stats.complete * 100f / stats.total);
        aiStatusView.setText(percent + "%");
        if (aiCompletionSummaryView != null && aiCompletionSummaryView.isAttachedToWindow()) {
            aiCompletionSummaryView.setText("  已补全 " + stats.complete + " / " + stats.total);
        }
        if (aiRunStateView != null && aiRunStateView.isAttachedToWindow()) {
            if (running) {
                aiRunStateView.setText("补全中");
                aiRunStateView.setTextColor(LILAC);
            } else if (stats.error > 0) {
                aiRunStateView.setText("需要重试");
                aiRunStateView.setTextColor(CORAL);
            } else if (stats.total > 0 && stats.complete == stats.total) {
                aiRunStateView.setText("已完成");
                aiRunStateView.setTextColor(MINT);
            } else {
                aiRunStateView.setText("待开始");
                aiRunStateView.setTextColor(MUTED);
            }
        }
        if (aiCompletedMetricView != null && aiCompletedMetricView.isAttachedToWindow()) aiCompletedMetricView.setText(String.valueOf(stats.complete));
        if (aiProcessingMetricView != null && aiProcessingMetricView.isAttachedToWindow()) aiProcessingMetricView.setText(String.valueOf(stats.processing));
        if (aiPendingMetricView != null && aiPendingMetricView.isAttachedToWindow()) aiPendingMetricView.setText(String.valueOf(stats.retryable()));
        if (aiProgressBar != null && aiProgressBar.isAttachedToWindow()) {
            setAiProgress(aiProgressBar, stats.total, stats.complete);
        }
        updateAiTaskProgress(running, stats, processed, requested);
        if (aiProgressView != null && aiProgressView.isAttachedToWindow()) {
            if (running) {
                aiProgressView.setText("本次任务 " + Math.min(processed, requested) + " / " + requested
                        + " · 已写入 " + aiTaskCompleted + " 条 · 可继续浏览和学习");
            } else if (stats.error > 0) {
                aiProgressView.setText("待补全 " + stats.retryable() + " 条 · " + stats.error + " 条失败，可再次补全重试");
            } else {
                aiProgressView.setText(stats.retryable() == 0 ? "AI 内容已补全。" : "待补全 " + stats.retryable() + " 条 · 可按需补全");
            }
        }
    }

    /** Separates current-run liveness from the long-lived wordbook completion percentage. */
    private void updateAiTaskProgress(boolean running, AiEnrichmentStats stats, int processed, int requested) {
        if (aiTaskProgressView == null || aiTaskProgressBar == null
                || !aiTaskProgressView.isAttachedToWindow() || !aiTaskProgressBar.isAttachedToWindow()) return;
        if (!running || requested <= 0) {
            aiTaskProgressView.setVisibility(View.GONE);
            aiTaskProgressBar.setIndeterminate(false);
            aiTaskProgressBar.setVisibility(View.GONE);
            return;
        }
        int handled = Math.min(Math.max(0, requested), Math.max(0, processed));
        aiTaskProgressView.setVisibility(View.VISIBLE);
        aiTaskProgressBar.setVisibility(View.VISIBLE);
        if (stats.processing > 0) {
            aiTaskProgressView.setText("本次补全 " + handled + " / " + requested + " · 正在生成 " + stats.processing + " 条");
            aiTaskProgressBar.setIndeterminate(true);
        } else {
            aiTaskProgressView.setText("本次补全 " + handled + " / " + requested + " · 正在写入结果");
            aiTaskProgressBar.setIndeterminate(false);
            setAiProgress(aiTaskProgressBar, requested, handled);
        }
    }

    private void setAiProgress(ProgressBar progress, int total, int complete) {
        int max = Math.max(1, total);
        int target = Math.min(max, Math.max(0, complete));
        progress.setMax(max);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) progress.setProgress(target, true);
        else progress.setProgress(target);
    }

    /** Opens a live, lightweight view of the background task without interrupting it. */
    private void showAiProgressPanel(long wordbookId, String wordbookName) {
        if (wordbookId <= 0) return;
        restoreLastAiRun(wordbookId);
        if (aiProgressDialog != null && aiProgressDialog.isShowing() && aiProgressDialogWordbookId == wordbookId) return;
        LinearLayout body = vertical(0);
        body.addView(label(wordbookName, 13, INK, Typeface.BOLD));
        TextView percent = label("0%", 30, LILAC, Typeface.BOLD);
        percent.setPadding(0, dp(14), 0, 0);
        percent.setIncludeFontPadding(false);
        body.addView(percent);
        TextView summary = label("已补全 0 / 0", 11, MUTED, Typeface.NORMAL);
        summary.setPadding(0, dp(3), 0, 0);
        body.addView(summary);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(LILAC));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(LILAC, darkMode ? 44 : 28)));
        LinearLayout.LayoutParams progressParams = fullHeight(9);
        progressParams.topMargin = dp(13);
        body.addView(progress, progressParams);
        TextView detail = label("正在准备补全任务…", 11, MUTED, Typeface.NORMAL);
        detail.setPadding(0, dp(12), 0, 0);
        body.addView(detail);
        TextView hint = label("关闭此窗口不会取消任务；词库总进度显示在主页面。", 9, MUTED, Typeface.NORMAL);
        hint.setPadding(0, dp(8), 0, 0);
        body.addView(hint);

        Dialog dialog = panel("AI 补全进度", body);
        Button close = primaryButton("关闭");
        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(close, new LinearLayout.LayoutParams(0, dp(48), 1));
        addPanelButtons(dialog, buttons);
        close.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            panelShells.remove(dialog);
            if (aiProgressDialog == dialog) {
                aiProgressDialog = null;
                aiProgressDialogWordbookId = -1;
                aiDialogPercentView = null;
                aiDialogSummaryView = null;
                aiDialogDetailView = null;
                aiDialogProgressBar = null;
            }
        });
        aiProgressDialog = dialog;
        aiProgressDialogWordbookId = wordbookId;
        aiDialogPercentView = percent;
        aiDialogSummaryView = summary;
        aiDialogDetailView = detail;
        aiDialogProgressBar = progress;
        dialog.show();
        AiEnrichmentStats cached = aiStatsCache.get(wordbookId);
        updateAiProgressDialog(wordbookId, cached == null ? new AiEnrichmentStats(0, 0, 0, 0, 0) : cached,
                aiTaskProcessed, aiTaskRequested);
    }

    private void updateAiProgressDialog(long wordbookId, AiEnrichmentStats stats, int processed, int requested) {
        if (aiProgressDialog == null || aiProgressDialogWordbookId != wordbookId || !aiProgressDialog.isShowing()
                || aiDialogPercentView == null || aiDialogProgressBar == null) return;
        boolean sameRun = aiTaskWordbookId == wordbookId && aiTaskRequested > 0;
        int runRequested = sameRun ? aiTaskRequested : requested;
        int runProcessed = sameRun ? (aiTaskRunning ? processed : aiTaskProcessed) : 0;
        int runCompleted = sameRun ? aiTaskCompleted : 0;
        if (sameRun) {
            int percent = runRequested == 0 ? 0 : Math.round(Math.min(runRequested, runProcessed) * 100f / runRequested);
            aiDialogPercentView.setText(percent + "%");
            if (aiDialogSummaryView != null) {
                aiDialogSummaryView.setText("本次补全 " + Math.min(runProcessed, runRequested) + " / " + runRequested
                        + " · 已写入 " + runCompleted + " 条");
            }
            setAiProgress(aiDialogProgressBar, runRequested, runProcessed);
        } else {
            int percent = stats.total == 0 ? 0 : Math.round(stats.complete * 100f / stats.total);
            aiDialogPercentView.setText(percent + "%");
            if (aiDialogSummaryView != null) {
                aiDialogSummaryView.setText("本次补全尚未开始 · 词库已补全 " + stats.complete + " / " + stats.total);
            }
            setAiProgress(aiDialogProgressBar, 1, 0);
        }
        if (aiDialogDetailView != null) {
            if (sameRun && aiTaskRunning) {
                aiDialogDetailView.setText("正在补全 · 已处理 " + Math.min(runProcessed, runRequested) + " / " + runRequested
                        + " · 词库总进度 " + stats.complete + " / " + stats.total);
            } else if (sameRun) {
                int failed = Math.max(0, runProcessed - runCompleted);
                aiDialogDetailView.setText("本次任务已结束 · 已写入 " + runCompleted + " 条"
                        + (failed == 0 ? "。" : " · " + failed + " 条失败，可再次补全重试。"));
            } else if (stats.error > 0) {
                aiDialogDetailView.setText("暂无本次任务 · 历史失败 " + stats.error + " 条，可点击 AI 补全重试。");
            } else if (stats.total > 0 && stats.complete == stats.total) {
                aiDialogDetailView.setText("暂无本次任务 · 词库内容已全部补全。");
            } else {
                aiDialogDetailView.setText("暂无本次任务；可关闭后点击 AI 补全选择数量。");
            }
        }
    }

    /** Keeps the latest per-wordbook run visible after navigating away or restarting the activity. */
    private String aiRunKey(long wordbookId, String field) {
        return "wordbook_" + wordbookId + "_" + field;
    }

    private void rememberLastAiRun(long wordbookId, int requested, int processed, int completed) {
        if (wordbookId <= 0 || requested <= 0) return;
        SharedPreferences.Editor editor = getSharedPreferences(AI_RUN_PREFS, MODE_PRIVATE).edit();
        editor.putInt(aiRunKey(wordbookId, "requested"), Math.max(0, requested));
        editor.putInt(aiRunKey(wordbookId, "processed"), Math.max(0, processed));
        editor.putInt(aiRunKey(wordbookId, "completed"), Math.max(0, completed));
        editor.apply();
    }

    private void restoreLastAiRun(long wordbookId) {
        if (aiTaskRunning || (aiTaskWordbookId == wordbookId && aiTaskRequested > 0)) return;
        SharedPreferences preferences = getSharedPreferences(AI_RUN_PREFS, MODE_PRIVATE);
        int requested = preferences.getInt(aiRunKey(wordbookId, "requested"), 0);
        if (requested <= 0) return;
        aiTaskWordbookId = wordbookId;
        aiTaskRequested = requested;
        aiTaskProcessed = preferences.getInt(aiRunKey(wordbookId, "processed"), 0);
        aiTaskCompleted = preferences.getInt(aiRunKey(wordbookId, "completed"), 0);
    }

    private void showAiCompletionPicker(String contentMode) {
        if (libraryWordbookId <= 0) {
            Toast.makeText(this, "请先选择一个具体词库", Toast.LENGTH_SHORT).show();
            return;
        }
        if (aiTaskRunning) {
            Toast.makeText(this, "AI 补全正在进行中", Toast.LENGTH_SHORT).show();
            return;
        }
        AiConfig config = aiConfigStore.load();
        if (config == null) {
            Toast.makeText(this, "请先配置 AI 服务", Toast.LENGTH_LONG).show();
            showAiSettingsPanel();
            return;
        }
        long wordbookId = libraryWordbookId;
        String wordbookName = libraryWordbookName;
        io.execute(() -> {
            try (MemoryDb local = new MemoryDb(getApplicationContext())) {
                AiEnrichmentStats stats = local.aiStats(wordbookId);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showAiQuantityPanel(wordbookId, wordbookName, contentMode, stats, config);
                });
            }
        });
    }

    private void showAiQuantityPanel(long wordbookId, String wordbookName, String contentMode, AiEnrichmentStats stats, AiConfig config) {
        int remaining = stats.retryable();
        if (remaining <= 0) {
            Toast.makeText(this, stats.processing > 0 ? "仍有补全任务在处理" : "这个词库已经没有待补全词条", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout body = vertical(0);
        body.addView(label("当前词库：" + wordbookName, 13, INK, Typeface.BOLD));
        TextView summary = label("总词条：" + stats.total + "\n已补全：" + stats.complete + "\n待补全：" + remaining, 11, MUTED, Typeface.NORMAL);
        summary.setPadding(0, dp(8), 0, 0);
        body.addView(summary);
        body.addView(fieldLabel("本次补全数量"));
        final int[] selected = {Math.min(20, remaining)};
        TextView chosen = label("本次将补全 " + selected[0] + " 条", 11, LILAC, Typeface.BOLD);
        LinearLayout choices = new LinearLayout(this);
        choices.setPadding(0, dp(7), 0, 0);
        for (int amount : new int[]{20, 50, 100}) {
            Button choice = quantityButton(String.valueOf(amount));
            choice.setOnClickListener(view -> { selected[0] = Math.min(amount, remaining); chosen.setText("本次将补全 " + selected[0] + " 条"); });
            choices.addView(choice, new LinearLayout.LayoutParams(0, dp(40), 1));
        }
        Button all = quantityButton("全部剩余");
        all.setOnClickListener(view -> { selected[0] = remaining; chosen.setText("本次将补全剩余 " + remaining + " 条"); });
        LinearLayout.LayoutParams allParams = fullHeight(40); allParams.topMargin = dp(7);
        body.addView(choices); body.addView(all, allParams);
        EditText custom = input("自定义正整数，例如 35", false);
        custom.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams customParams = fullHeight(48); customParams.topMargin = dp(8);
        body.addView(custom, customParams);
        chosen.setPadding(0, dp(9), 0, 0); body.addView(chosen);

        Dialog dialog = panel("AI 补全", body);
        LinearLayout buttons = new LinearLayout(this);
        Button cancel = linkButton("取消");
        Button start = primaryButton("开始补全");
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, dp(48), 1); startParams.setMarginStart(dp(8));
        buttons.addView(start, startParams);
        addPanelButtons(dialog, buttons);
        cancel.setOnClickListener(view -> dialog.dismiss());
        start.setOnClickListener(view -> {
            int count = selected[0];
            String entered = custom.getText().toString().trim();
            if (!entered.isEmpty()) {
                try { count = Integer.parseInt(entered); }
                catch (NumberFormatException ignored) { count = 0; }
            }
            if (count <= 0) { custom.setError("请输入正整数"); return; }
            if (count > remaining) {
                count = remaining;
                custom.setText(String.valueOf(count));
                chosen.setText("本次将补全剩余 " + count + " 条");
                Toast.makeText(this, "数量已调整为剩余 " + count + " 条", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            startAiEnrichment(wordbookId, contentMode, count, config);
        });
        dialog.show();
    }

    private Button quantityButton(String text) {
        Button button = new Button(this);
        button.setText(text); button.setTextSize(10); button.setAllCaps(false); button.setTextColor(LILAC);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(rounded(withAlpha(LILAC, darkMode ? 34 : 17), 12, withAlpha(LILAC, darkMode ? 95 : 60)));
        applyFlatButtonBehavior(button);
        return button;
    }

    private void startAiEnrichment(long wordbookId, String contentMode, int count, AiConfig config) {
        if (aiTaskRunning) return;
        aiTaskRunning = true;
        aiTaskWordbookId = wordbookId;
        aiTaskRequested = count;
        aiTaskProcessed = 0;
        aiTaskCompleted = 0;
        updateAiCompletionButton();
        AiEnrichmentStats cachedStats = aiStatsCache.get(wordbookId);
        if (cachedStats != null) updateAiStatusViews(wordbookId, cachedStats, 0, count);
        if (libraryWordbookId == wordbookId && aiProgressView != null && aiProgressView.isAttachedToWindow()) {
            aiProgressView.setText("正在连接 AI 服务…本次将补全 " + count + " 条");
        }
        io.execute(() -> {
            AiEnrichmentRunner.Outcome outcome;
            try (MemoryDb local = new MemoryDb(getApplicationContext())) {
                AiEnrichmentRunner runner = new AiEnrichmentRunner(local, new AiApi());
                outcome = runner.run(wordbookId, contentMode, count, config, new AiEnrichmentRunner.Progress() {
                    @Override public void onBatchStarted(int processed, int requested, int completed, AiEnrichmentStats stats) {
                        runOnUiThread(() -> {
                            aiTaskProcessed = processed;
                            aiTaskCompleted = completed;
                            updateAiStatusViews(wordbookId, stats, processed, requested);
                        });
                    }

                    @Override public void onBatch(int processed, int requested, int completed, AiEnrichmentStats stats) {
                        runOnUiThread(() -> {
                            aiTaskProcessed = processed;
                            aiTaskCompleted = completed;
                            updateAiStatusViews(wordbookId, stats, processed, requested);
                        });
                    }
                });
            } catch (Exception error) {
                Log.w(AI_LOG_TAG, "AI task could not start; wordbook=" + wordbookId, error);
                outcome = new AiEnrichmentRunner.Outcome(count, 0, 0, "AI 补全未能启动");
            }
            AiEnrichmentRunner.Outcome finalOutcome = outcome;
            runOnUiThread(() -> {
                aiTaskRunning = false;
                aiTaskProcessed = finalOutcome.processed;
                aiTaskCompleted = finalOutcome.completed;
                rememberLastAiRun(wordbookId, finalOutcome.requested, finalOutcome.processed, finalOutcome.completed);
                updateAiCompletionButton();
                AiEnrichmentStats finishedStats = aiStatsCache.get(wordbookId);
                if (finishedStats != null) updateAiStatusViews(wordbookId, finishedStats, aiTaskProcessed, aiTaskRequested);
                if (!isFinishing() && !isDestroyed()) {
                    loadAiStatsAsync(wordbookId);
                    String message = finalOutcome.error.isEmpty()
                            ? "AI 补全完成：" + finalOutcome.completed + " / " + finalOutcome.requested
                            : "AI 补全暂停：" + finalOutcome.error;
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void showAiSettingsPanel() {
        AiConfig existing = aiConfigStore.load();
        LinearLayout body = vertical(0);
        body.addView(label("OpenAI-compatible 服务", 13, INK, Typeface.BOLD));
        body.addView(label("支持 GPT-OSS、Ollama compatible endpoint 和其他兼容服务。API Key 可留空。", 10, MUTED, Typeface.NORMAL));
        body.addView(fieldLabel("Base URL"));
        EditText baseUrl = input("http://localhost:11434/v1", false); if (existing != null) baseUrl.setText(existing.baseUrl);
        body.addView(baseUrl, fullHeight(48));
        body.addView(fieldLabel("API Key（可选）"));
        EditText apiKey = input("本地服务通常无需填写", true); if (existing != null) apiKey.setText(existing.apiKey);
        body.addView(apiKey, fullHeight(48));
        Button fetchModels = paginationButton("↻  获取模型列表");
        LinearLayout.LayoutParams fetchParams = fullHeight(44); fetchParams.topMargin = dp(10); body.addView(fetchModels, fetchParams);
        TextView modelStatus = label("填写服务地址后即可读取可用模型。", 10, MUTED, Typeface.NORMAL);
        modelStatus.setPadding(0, dp(7), 0, 0); body.addView(modelStatus);
        body.addView(fieldLabel("模型"));
        EditText model = input("从下方列表选择，或手动填写", false); if (existing != null) model.setText(existing.model);
        body.addView(model, fullHeight(48));
        LinearLayout modelChoices = vertical(0);
        modelChoices.setPadding(0, dp(8), 0, 0);
        modelChoices.setVisibility(View.GONE);
        body.addView(modelChoices);
        final boolean[] loadingModels = {false};
        View.OnClickListener loadModels = view -> {
            if (loadingModels[0]) return;
            String requestedUrl = baseUrl.getText().toString().trim();
            if (requestedUrl.isEmpty()) { baseUrl.setError("请先填写 Base URL"); return; }
            loadingModels[0] = true;
            fetchModels.setEnabled(false);
            modelStatus.setTextColor(LILAC);
            modelStatus.setText("正在读取可用模型…");
            modelChoices.setVisibility(View.GONE);
            String requestedKey = apiKey.getText().toString();
            io.execute(() -> {
                try {
                    List<String> models = new AiApi().listModels(requestedUrl, requestedKey);
                    runOnUiThread(() -> {
                        loadingModels[0] = false;
                        fetchModels.setEnabled(true);
                        modelChoices.removeAllViews();
                        int shown = Math.min(50, models.size());
                        modelStatus.setTextColor(MINT);
                        modelStatus.setText("请选择模型（" + shown + " 个可用）");
                        for (int index = 0; index < shown; index++) {
                            String modelId = models.get(index);
                            Button option = modelChoiceButton(modelId);
                            option.setOnClickListener(choice -> {
                                model.setText(modelId);
                                modelChoices.setVisibility(View.GONE);
                                modelStatus.setTextColor(MINT);
                                modelStatus.setText("已选择模型：" + modelId);
                            });
                            LinearLayout.LayoutParams optionParams = fullHeight(42);
                            if (index > 0) optionParams.topMargin = dp(6);
                            modelChoices.addView(option, optionParams);
                        }
                        modelChoices.setVisibility(View.VISIBLE);
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        loadingModels[0] = false;
                        fetchModels.setEnabled(true);
                        modelStatus.setTextColor(CORAL);
                        modelStatus.setText("无法读取模型：" + safeAiMessage(error));
                    });
                }
            });
        };
        fetchModels.setOnClickListener(loadModels);
        Dialog dialog = panel("配置 AI 服务", body);
        LinearLayout buttons = new LinearLayout(this);
        Button cancel = linkButton("稍后"); Button save = primaryButton("保存配置");
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(48), 1); saveParams.setMarginStart(dp(8)); buttons.addView(save, saveParams);
        addPanelButtons(dialog, buttons);
        cancel.setOnClickListener(view -> dialog.dismiss());
        save.setOnClickListener(view -> {
            AiConfig config = new AiConfig(baseUrl.getText().toString(), model.getText().toString(), apiKey.getText().toString());
            if (config.baseUrl.isEmpty()) { baseUrl.setError("请填写 Base URL"); return; }
            if (config.model.isEmpty()) { model.setError("请选择或填写模型"); return; }
            save.setEnabled(false);
            io.execute(() -> {
                try {
                    new AiConfigStore(getApplicationContext()).save(config);
                    runOnUiThread(() -> { dialog.dismiss(); Toast.makeText(this, "AI 服务已保存", Toast.LENGTH_SHORT).show(); });
                } catch (Exception error) {
                    runOnUiThread(() -> { save.setEnabled(true); Toast.makeText(this, "无法保存 AI 配置", Toast.LENGTH_LONG).show(); });
                }
            });
        });
        dialog.show();
        // Reopening settings should immediately offer the models from the already-saved service.
        if (existing != null && !existing.baseUrl.isEmpty()) fetchModels.post(fetchModels::performClick);
    }

    private Button modelChoiceButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setTextColor(INK);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(dp(13), 0, dp(13), 0);
        button.setBackground(rounded(CARD_RAISED, 12, LINE));
        applyFlatButtonBehavior(button);
        return button;
    }

    private String safeAiMessage(Exception error) {
        String message = error == null ? "请检查服务地址、网络和 API Key" : error.getMessage();
        if (message == null || message.trim().isEmpty()) return "请检查服务地址、网络和 API Key";
        return message.length() > 100 ? message.substring(0, 100) : message;
    }

    private Dialog panel(String heading, LinearLayout body) {
        Dialog dialog = new Dialog(this);
        LinearLayout shell = card();
        shell.setTag("linguabridge-panel-shell");
        shell.setPadding(dp(20), dp(19), dp(20), dp(15));
        shell.addView(title(heading, 21));
        LinearLayout.LayoutParams bodyParams = fullWrap(); bodyParams.topMargin = dp(10); shell.addView(body, bodyParams);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setPadding(dp(18), dp(20), dp(18), dp(20));
        scroll.addView(shell, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(scroll);
        panelShells.put(dialog, shell);
        dialog.setOnDismissListener(ignored -> panelShells.remove(dialog));
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        return dialog;
    }

    private void addPanelButtons(Dialog dialog, LinearLayout buttons) {
        LinearLayout shell = panelShells.get(dialog);
        if (shell == null) return;
        LinearLayout.LayoutParams params = fullWrap(); params.topMargin = dp(16); shell.addView(buttons, params);
    }

    private void runPhoneticBackfill() {
        io.execute(() -> {
            try (MemoryDb local = new MemoryDb(getApplicationContext()); PhoneticDictionary dictionary = new PhoneticDictionary(getApplicationContext())) {
                local.backfillMissingPhonetics(dictionary);
            } catch (Exception ignored) {
                // Optional offline enhancement must never affect app startup or importing.
            }
        });
    }

    private Button paginationButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTextColor(BLUE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(rounded(CARD_RAISED, 13, LINE));
        applyFlatButtonBehavior(button);
        return button;
    }

    private LinearLayout wordbookSelector(
            String name, String detail, int total, int due, int fresh,
            boolean selected, String monogram, int accent, Runnable action
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(13), dp(12), dp(13), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription("词库 " + name + "，" + total + " 个词，待复习 " + due + "，新词 " + fresh);
        row.setBackground(rounded(
                selected ? withAlpha(accent, darkMode ? 48 : 22) : CARD_RAISED,
                17,
                selected ? withAlpha(accent, darkMode ? 130 : 100) : LINE
        ));
        row.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        });
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = label(monogram, 11, selected ? Color.WHITE : accent, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(selected ? accent : withAlpha(accent, darkMode ? 48 : 24), 12));
        heading.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout names = vertical(0);
        names.setPadding(dp(10), 0, dp(8), 0);
        names.addView(label(name, 13, INK, Typeface.BOLD));
        names.addView(label(detail, 9, MUTED, Typeface.NORMAL));
        heading.addView(names, weighted());
        if (selected) heading.addView(statusPill("已选择", accent));
        row.addView(heading);
        row.addView(wordbookMetrics(total, due, fresh));
        return row;
    }

    /**
     * A left-swipe action drawer for custom wordbooks. The action surface tracks the foreground
     * edge, so it is never visible underneath the selected card's translucent background.
     * database work begins after the gesture settles, and diagonal scrolls remain vertical.
     */
    private final class WordbookSwipeActionRow extends FrameLayout {
        private final View foreground;
        private final View actionSurface;
        private final float maxReveal;
        private final int touchSlop;
        private float downX, downY, startingTranslation;
        private boolean dragging;

        WordbookSwipeActionRow(Wordbook book, View foreground) {
            super(MainActivity.this);
            this.foreground = foreground;
            maxReveal = dp(150);
            touchSlop = android.view.ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
            LinearLayout actions = new LinearLayout(MainActivity.this);
            actionSurface = actions;
            actions.setGravity(Gravity.CENTER_VERTICAL);
            actions.setPadding(dp(7), 0, dp(7), 0);
            actions.setBackground(rounded(withAlpha(LILAC, darkMode ? 44 : 24), 17, withAlpha(LILAC, darkMode ? 95 : 62)));
            Button pin = linkButton(book.pinnedAt > 0 ? "取消置顶" : "置顶");
            pin.setTextColor(LILAC);
            pin.setOnClickListener(view -> {
                closeDrawer(() -> io.execute(() -> {
                    try (MemoryDb local = new MemoryDb(getApplicationContext())) { local.setWordbookPinned(book.id, book.pinnedAt == 0); }
                    runOnUiThread(() -> {
                        if (!isFinishing()) refreshLibraryAfterManagementChange(book.id, book.pinnedAt == 0);
                    });
                }));
            });
            Button remove = linkButton("删除");
            remove.setTextColor(CORAL);
            remove.setOnClickListener(view -> closeDrawer(() -> showDeleteWordbookPanel(book)));
            actions.addView(pin, new LinearLayout.LayoutParams(dp(76), dp(46)));
            actions.addView(remove, new LinearLayout.LayoutParams(dp(58), dp(46)));
            addView(actions, new FrameLayout.LayoutParams((int) maxReveal, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
            // Start beyond the right edge. It slides left exactly as far as the card slides left.
            actions.setTranslationX(maxReveal);
            addView(foreground, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    foreground.animate().cancel();
                    actionSurface.animate().cancel();
                    downX = event.getX(); downY = event.getY(); startingTranslation = foreground.getTranslationX(); dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    float earlyHorizontalSlop = Math.max(dp(3), touchSlop * 0.5f);
                    boolean openingTendency = dx < -earlyHorizontalSlop;
                    boolean closingTendency = startingTranslation < 0f && dx > earlyHorizontalSlop;
                    // Claim the gesture early so a horizontal swipe never also moves the list vertically.
                    if ((openingTendency || closingTendency) && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    boolean openingLeft = dx < -touchSlop;
                    boolean closingRight = startingTranslation < 0f && dx > touchSlop;
                    boolean horizontalAction = (openingLeft || closingRight) && Math.abs(dx) > Math.abs(dy) * 1.35f;
                    if (horizontalAction) getParent().requestDisallowInterceptTouchEvent(true);
                    if (horizontalAction) {
                        dragging = true;
                        foreground.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    float translation = Math.min(0f, Math.max(-maxReveal, startingTranslation + event.getX() - downX));
                    foreground.setTranslationX(translation);
                    actionSurface.setTranslationX(maxReveal + translation);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) return false;
                    if (Math.abs(event.getX() - downX) > touchSlop) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    settle(-foreground.getTranslationX() > maxReveal * 0.42f);
                    dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) return false;
                    settle(-foreground.getTranslationX() > maxReveal * 0.42f);
                    dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return dragging;
        }

        private void closeDrawer(Runnable afterClosed) { settle(false, afterClosed); }

        private void settle(boolean open) { settle(open, null); }

        private void settle(boolean open, Runnable afterSettled) {
            float destination = open ? -maxReveal : 0f;
            foreground.animate().translationX(destination).setDuration(180).setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                foreground.setLayerType(View.LAYER_TYPE_NONE, null);
                if (afterSettled != null) afterSettled.run();
            }).start();
            actionSurface.animate().translationX(maxReveal + destination).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }

        @Override protected void onDetachedFromWindow() {
            foreground.animate().cancel();
            actionSurface.animate().cancel();
            foreground.setTranslationX(0f);
            actionSurface.setTranslationX(maxReveal);
            super.onDetachedFromWindow();
        }
    }

    private void showDeleteWordbookPanel(Wordbook book) {
        LinearLayout body = vertical(0);
        body.addView(label("确定从当前设备删除“" + book.name + "”吗？", 14, INK, Typeface.BOLD));
        TextView detail = label("词条和相关复习记录将一并删除。已同步词库会在此设备保留忽略标记，不会被自动下载回来。", 11, MUTED, Typeface.NORMAL);
        detail.setPadding(0, dp(8), 0, 0); body.addView(detail);
        Dialog dialog = panel("确认删除词库", body);
        LinearLayout buttons = new LinearLayout(this);
        Button cancel = linkButton("取消"); Button remove = primaryButton("确认删除"); remove.setTextColor(Color.WHITE);
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, dp(48), 1); removeParams.setMarginStart(dp(8)); buttons.addView(remove, removeParams);
        addPanelButtons(dialog, buttons);
        cancel.setOnClickListener(view -> dialog.dismiss());
        remove.setOnClickListener(view -> {
            remove.setEnabled(false);
            io.execute(() -> {
                try (MemoryDb local = new MemoryDb(getApplicationContext())) {
                    local.deleteWordbook(book.id);
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        if (libraryWordbookId == book.id) { libraryWordbookId = 0; libraryWordbookName = "全部"; libraryOffset = 0; }
                        showLibrary();
                        Toast.makeText(this, "已从当前设备删除词库", Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> { remove.setEnabled(true); Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); });
                }
            });
        });
        dialog.show();
    }

    private LinearLayout wordbookMetrics(int total, int due, int fresh) {
        LinearLayout metrics = new LinearLayout(this);
        metrics.setPadding(dp(38), dp(10), 0, 0);
        metrics.addView(wordbookMetric("词条", total, INK), weighted());
        metrics.addView(wordbookMetric("待复习", due, due > 0 ? CORAL : MUTED), weighted());
        metrics.addView(wordbookMetric("新词", fresh, fresh > 0 ? BLUE : MUTED), weighted());
        return metrics;
    }

    private LinearLayout librarySummary(int total, int due, int fresh) {
        LinearLayout summary = wordbookMetrics(total, due, fresh);
        summary.setPadding(0, dp(12), 0, 0);
        return summary;
    }

    private LinearLayout wordbookMetric(String title, int value, int color) {
        LinearLayout metric = vertical(0);
        metric.addView(label(String.valueOf(value), 18, color, Typeface.BOLD));
        metric.addView(label(title, 9, MUTED, Typeface.NORMAL));
        return metric;
    }

    private LinearLayout libraryEmptyState() {
        LinearLayout empty = vertical(0);
        empty.setPadding(dp(17), dp(16), dp(17), dp(16));
        applyInsetSurfaceStyle(empty, 18);
        empty.addView(label("从第一组词开始", 14, INK, Typeface.BOLD));
        TextView detail = label("导入自己的词库，或连接桌面后自动接收翻译内容。", 11, MUTED, Typeface.NORMAL);
        detail.setPadding(0, dp(6), 0, 0);
        empty.addView(detail);
        return empty;
    }

    private void uploadSelectedWordbook(Button button) {
        SyncConfig config = secureStore.load();
        if (config == null) {
            Toast.makeText(this, "请先在“连接桌面”中连接同步空间", Toast.LENGTH_LONG).show();
            return;
        }
        button.setEnabled(false);
        button.setText("正在加密并同步…");
        io.execute(() -> {
            try (MemoryDb local = new MemoryDb(getApplicationContext())) {
                MemoryDb.CloudSnapshot snapshot = local.prepareWordbookSync(libraryWordbookId, config.deviceId);
                org.json.JSONObject result = CloudApi.uploadWordbook(config, snapshot);
                long version = result.optLong("version", snapshot.version + 1);
                local.markWordbookPublished(snapshot, version);
                runOnUiThread(() -> Toast.makeText(this, "词库已加密同步到其他设备（版本 " + version + "）", Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this, "词库同步失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                runOnUiThread(() -> { if (!isFinishing()) { button.setEnabled(true); button.setText("同步到已连接的其他设备"); } });
            }
        });
    }

    private View libraryRow(MemoryCard memoryCard) {
        LinearLayout row = card();
        LinearLayout.LayoutParams params = fullWrap();
        params.topMargin = dp(10);
        row.setLayoutParams(params);
        TextView kind = label("word".equals(memoryCard.type) ? "WORD" : "SENTENCE", 9, BLUE, Typeface.BOLD);
        row.addView(kind);
        TextView front = label(memoryCard.front, 18, INK, Typeface.BOLD);
        front.setPadding(0, dp(5), 0, 0);
        row.addView(front);
        if (!memoryCard.phonetic.isEmpty()) {
            TextView ipa = label(IpaFormatter.formatIpaForDisplay(memoryCard.phonetic), 15, LILAC, Typeface.NORMAL);
            ipa.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            ipa.setLetterSpacing(0.018f);
            ipa.setIncludeFontPadding(false);
            row.addView(ipa);
        }
        TextView back = label(memoryCard.back, 13, MUTED, Typeface.NORMAL);
        back.setPadding(0, dp(7), 0, 0);
        row.addView(back);
        boolean technicalAi = isTechnicalAi(memoryCard);
        TextView analysisHeading = label(technicalAi ? "技术术语解析" : "AI解析 · 通用词汇", 10, LILAC, Typeface.BOLD);
        analysisHeading.setPadding(0, dp(9), 0, 0);
        row.addView(analysisHeading);
        if ((technicalAi || memoryCard.aiMode.isEmpty()) && !memoryCard.technicalNotes.isEmpty()) {
            TextView note = label(memoryCard.technicalNotes.get(0), 13,
                    darkMode ? Color.rgb(190, 177, 208) : Color.rgb(91, 78, 103), Typeface.NORMAL);
            row.addView(note);
        }
        if (!memoryCard.category.isEmpty()) row.addView(label(memoryCard.category, 11, BLUE, Typeface.NORMAL));
        if (!memoryCard.context.isEmpty()) row.addView(label(memoryCard.context, 14, MUTED, Typeface.NORMAL));
        if (!memoryCard.contextTranslation.isEmpty()) {
            row.addView(label("中文翻译", 10, BLUE, Typeface.BOLD));
            row.addView(label(memoryCard.contextTranslation, 14, MUTED, Typeface.NORMAL));
        } else if (!memoryCard.context.isEmpty() && "pending".equals(memoryCard.aiStatus)) {
            row.addView(label("中文翻译待补全", 11, MUTED, Typeface.NORMAL));
        }
        if (!hasAiAnalysis(memoryCard)) row.addView(label(aiAnalysisFallback(memoryCard), 10, MUTED, Typeface.NORMAL));
        return row;
    }

    private boolean hasAiAnalysis(MemoryCard memoryCard) {
        return memoryCard != null && (!memoryCard.context.trim().isEmpty()
                || !memoryCard.contextTranslation.trim().isEmpty() || !memoryCard.technicalNotes.isEmpty());
    }

    private boolean isTechnicalAi(MemoryCard memoryCard) {
        return memoryCard != null && "technical".equals(memoryCard.aiMode);
    }

    /** A failed or pending request remains actionable information, never an empty "AI解析" panel. */
    private String aiAnalysisFallback(MemoryCard memoryCard) {
        if (memoryCard == null) return "AI解析尚未生成。";
        if ("processing".equals(memoryCard.aiStatus)) return "AI解析生成中，完成后会自动显示。";
        if ("error".equals(memoryCard.aiStatus)) {
            String detail = conciseAiError(memoryCard.aiError);
            return detail.isEmpty() ? "AI解析生成失败，可在词库中再次补全。" : "AI解析生成失败：" + detail;
        }
        if ("complete".equals(memoryCard.aiStatus)) return "AI解析内容为空，可再次补全。";
        return "AI解析尚未生成，可在词库中按需补全。";
    }

    private String conciseAiError(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("{")) {
            try { value = new org.json.JSONObject(value).optString("message", value); }
            catch (Exception ignored) { /* A readable raw message is still better than a blank panel. */ }
        }
        value = value.replaceAll("\\s+", " ").trim();
        return value.length() > 110 ? value.substring(0, 110) + "…" : value;
    }

    private void beginReview(long wordbookId) {
        reviewWordbookId = wordbookId;
        reviewSessionStartedAt = System.currentTimeMillis();
        reviewPracticeMode = db.nextDue(reviewSessionStartedAt, wordbookId) == null;
        reviewSessionTotal = reviewPracticeMode
                ? db.practiceCount(reviewSessionStartedAt, wordbookId)
                : db.dueCount(reviewSessionStartedAt, wordbookId);
        reviewCards.clear();
        reviewRatings.clear();
        reviewBaselines.clear();
        reviewCardIndex = -1;
        reviewSessionId = UUID.randomUUID().toString();
        revealedReviewCardId = -1;
        reviewProgressAnimationStart = 0;
        animateReviewProgress = false;
        MemoryCard first = nextUnseenReviewCard();
        if (first != null) {
            reviewCards.add(first);
            reviewCardIndex = 0;
        }
        showReview();
    }

    private void showReview() {
        reviewAdvancing = false;
        selectPage("home");
        currentPage = "review";
        final boolean switchingCards = reviewCardSwitching;
        reviewCardSwitching = false;
        final MemoryCard memoryCard = reviewCardIndex >= 0 && reviewCardIndex < reviewCards.size()
                ? reviewCards.get(reviewCardIndex)
                : null;
        if (memoryCard == null) {
            reviewSwipeStage = null;
            LinearLayout body = pageBody();
            body.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView check = label("✓", 36, Color.WHITE, Typeface.BOLD);
            check.setGravity(Gravity.CENTER);
            check.setBackground(gradient(new int[]{BLUE, CORAL}, 30));
            body.addView(check, new LinearLayout.LayoutParams(dp(60), dp(60)));
            TextView done = title(reviewPracticeMode ? "巩固完成" : "这一轮完成", 26);
            done.setPadding(0, dp(18), 0, 0);
            body.addView(done);
            body.addView(subtitle(reviewPracticeMode
                    ? "这一轮可练习的内容都回忆过了。明天也可以继续巩固。"
                    : "下次复习时间已按你的反馈安排。"));
            Button back = primaryButton("返回今天");
            back.setOnClickListener(view -> showHome());
            LinearLayout.LayoutParams params = fullHeight(52);
            params.topMargin = dp(20);
            body.addView(back, params);
            if (switchingCards) suppressNextPageAnimation = true;
            setPage(body);
            return;
        }

        LinearLayout body = pageBody();
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(eyebrow(reviewPracticeMode ? "PRACTICE" : "ACTIVE RECALL"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        body.addView(toolbar);

        LinearLayout progress = reviewProgressSummary();
        LinearLayout.LayoutParams progressParams = fullHeight(70);
        progressParams.topMargin = dp(12);
        body.addView(progress, progressParams);

        final String savedRating = reviewRatings.get(memoryCard.id);
        final boolean recordedThisSession = savedRating != null;
        final boolean answerRevealed = revealedReviewCardId == memoryCard.id;
        LinearLayout reviewCard = card();
        reviewCard.setGravity(Gravity.CENTER_HORIZONTAL);
        reviewCard.setPadding(dp(22), dp(28), dp(22), dp(24));
        TextView kind = label("word".equals(memoryCard.type) ? "WORD" : "SENTENCE", 10, BLUE, Typeface.BOLD);
        reviewCard.addView(kind);
        TextView front = label(memoryCard.front, "word".equals(memoryCard.type) ? 30 : 23, INK, Typeface.BOLD);
        front.setGravity(Gravity.CENTER);
        front.setPadding(0, dp(15), 0, 0);
        reviewCard.addView(front, fullWrap());
        if (!memoryCard.phonetic.isEmpty()) {
            TextView ipa = label(IpaFormatter.formatIpaForDisplay(memoryCard.phonetic), 18, LILAC, Typeface.NORMAL);
            ipa.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            ipa.setLetterSpacing(0.018f);
            ipa.setIncludeFontPadding(false);
            ipa.setGravity(Gravity.CENTER);
            ipa.setPadding(0, dp(8), 0, 0);
            reviewCard.addView(ipa, fullWrap());
        }
        Button speak = linkButton("朗读");
        speak.setOnClickListener(view -> speak(memoryCard));
        reviewCard.addView(speak);

        TextView swipeHint = label(recordedThisSession
                ? "本轮已有评分；显示答案后可修改 · 右滑返回上一张"
                : "左滑轻松并进入下一张 · 右滑返回上一张", 10, MUTED, Typeface.NORMAL);
        swipeHint.setGravity(Gravity.CENTER);
        swipeHint.setPadding(0, dp(8), 0, 0);
        reviewCard.addView(swipeHint);

        Button reveal = primaryButton("显示答案");
        LinearLayout.LayoutParams revealParams = fullHeight(50);
        revealParams.topMargin = dp(20);
        reviewCard.addView(reveal, revealParams);

        LinearLayout answer = vertical(10);
        answer.setVisibility(answerRevealed ? View.VISIBLE : View.GONE);
        answer.setPadding(0, dp(22), 0, 0);
        answer.addView(label("答案", 10, MUTED, Typeface.BOLD));
        answer.addView(label(memoryCard.back, 20, INK, Typeface.BOLD));
        if (!memoryCard.category.isEmpty()) answer.addView(label(memoryCard.category, 12, BLUE, Typeface.NORMAL));
        boolean technicalAi = isTechnicalAi(memoryCard);
        answer.addView(sectionLabel(technicalAi ? "技术术语解析" : "AI解析 · 通用词汇"));
        if (!memoryCard.context.isEmpty()) {
            answer.addView(sectionLabel("原句语境"));
            answer.addView(label(memoryCard.context, 15, MUTED, Typeface.NORMAL));
        }
        if (!memoryCard.contextTranslation.isEmpty()) {
            answer.addView(sectionLabel("中文翻译"));
            answer.addView(label(memoryCard.contextTranslation, 15, INK, Typeface.NORMAL));
        } else if (!memoryCard.context.isEmpty() && "pending".equals(memoryCard.aiStatus)) {
            answer.addView(sectionLabel("中文翻译"));
            answer.addView(label("中文翻译待补全，请再次运行 AI 补全。", 14, MUTED, Typeface.NORMAL));
        }
        if ((technicalAi || memoryCard.aiMode.isEmpty()) && !memoryCard.technicalNotes.isEmpty()) {
            answer.addView(sectionLabel("技术关联"));
            for (String note : memoryCard.technicalNotes) {
                answer.addView(label("• " + note, 14,
                        darkMode ? Color.rgb(202, 190, 219) : Color.rgb(88, 75, 100), Typeface.NORMAL));
            }
        }
        if (!hasAiAnalysis(memoryCard)) {
            answer.addView(label(aiAnalysisFallback(memoryCard), 12, MUTED, Typeface.NORMAL));
        }
        reviewCard.addView(answer, fullWrap());

        LinearLayout ratings = new LinearLayout(this);
        ratings.setVisibility(answerRevealed ? View.VISIBLE : View.GONE);
        ratings.setPadding(0, dp(16), 0, 0);
        addRating(ratings, reviewCard, memoryCard, "忘记", "again", Color.rgb(197, 67, 55));
        addRating(ratings, reviewCard, memoryCard, "模糊", "hard", Color.rgb(180, 119, 28));
        addRating(ratings, reviewCard, memoryCard, "记得", "good", BLUE);
        addRating(ratings, reviewCard, memoryCard, "轻松", "easy", Color.rgb(40, 132, 93));
        reviewCard.addView(ratings, fullWrap());
        if (answerRevealed) reveal.setVisibility(View.GONE);
        reveal.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            revealedReviewCardId = memoryCard.id;
            revealReviewAnswer(reveal, answer, ratings, swipeHint, -1);
        });

        ReviewSwipeStage stage = new ReviewSwipeStage();
        MemoryCard nextCard = reviewCardIndex + 1 < reviewCards.size()
                ? reviewCards.get(reviewCardIndex + 1)
                : nextUnseenReviewCard();
        MemoryCard previousCard = reviewCardIndex > 0 ? reviewCards.get(reviewCardIndex - 1) : null;
        stage.setCards(
                reviewCard,
                nextCard == null ? null : reviewPreviewCard(nextCard),
                previousCard == null ? null : reviewPreviewCard(previousCard)
        );
        reviewSwipeStage = stage;
        LinearLayout.LayoutParams cardParams = fullWrap();
        cardParams.topMargin = dp(12);
        body.addView(stage, cardParams);
        if (switchingCards) suppressNextPageAnimation = true;
        setPage(body);
    }

    private LinearLayout reviewProgressSummary() {
        int learned = reviewProgressValue();
        int waiting = Math.max(0, reviewSessionTotal - learned);
        int progressStart = animateReviewProgress
                ? Math.min(Math.max(0, reviewProgressAnimationStart), reviewSessionTotal)
                : learned;
        boolean shouldAnimateProgress = animateReviewProgress && progressStart != learned;
        animateReviewProgress = false;
        LinearLayout summary = vertical(0);
        summary.setPadding(dp(2), dp(2), dp(2), 0);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label("本轮复习进度", 11, INK, Typeface.BOLD), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        heading.addView(label("已复习 " + learned + " / " + reviewSessionTotal, 11, LILAC, Typeface.BOLD));
        summary.addView(heading);

        ReviewProgressBar bar = new ReviewProgressBar();
        bar.setProgress(progressStart, false);
        LinearLayout.LayoutParams barParams = fullHeight(10);
        barParams.topMargin = dp(7);
        summary.addView(bar, barParams);
        if (shouldAnimateProgress) bar.post(() -> bar.setProgress(learned, true));

        LinearLayout caption = new LinearLayout(this);
        caption.setPadding(0, dp(5), 0, 0);
        caption.addView(label("已复习 " + learned, 10, BLUE, Typeface.NORMAL), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView pending = label("待复习 " + waiting, 10, MUTED, Typeface.NORMAL);
        pending.setGravity(Gravity.RIGHT);
        caption.addView(pending, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        summary.addView(caption);
        return summary;
    }

    private int reviewProgressValue() {
        return Math.min(reviewRatings.size(), reviewSessionTotal);
    }

    /** A compact progress meter with a moving crystal highlight whenever progress changes. */
    private final class ReviewProgressBar extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sparklePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private float progress;
        private float shimmerPosition = 0.72f;
        private ValueAnimator animator;

        ReviewProgressBar() {
            super(MainActivity.this);
            trackPaint.setColor(darkMode ? Color.rgb(67, 59, 80) : Color.rgb(230, 225, 239));
        }

        void setProgress(int value, boolean animate) {
            float target = Math.max(0f, Math.min(1f,
                    value / (float) Math.max(1, reviewSessionTotal)));
            if (animator != null) animator.cancel();
            if (!animate || getWidth() == 0) {
                progress = target;
                shimmerPosition = 0.72f;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(520);
            animator.setInterpolator(new android.view.animation.PathInterpolator(
                    0.20f, 0.92f, 0.28f, 1f
            ));
            animator.addUpdateListener(animation -> {
                progress = (float) animation.getAnimatedValue();
                shimmerPosition = -0.20f + animation.getAnimatedFraction() * 1.45f;
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            if (width <= 0f || height <= 0f) return;
            float radius = height / 2f;
            bounds.set(0f, 0f, width, height);
            canvas.drawRoundRect(bounds, radius, radius, trackPaint);

            float fillWidth = width * progress;
            if (fillWidth <= 0f) return;
            fillPaint.setShader(new LinearGradient(
                    0f, 0f, width, 0f,
                    new int[]{
                            Color.rgb(94, 76, 244),
                            Color.rgb(78, 184, 255),
                            Color.rgb(187, 241, 255),
                            Color.rgb(183, 106, 255)
                    },
                    new float[]{0f, 0.36f, 0.60f, 1f},
                    Shader.TileMode.CLAMP
            ));
            int save = canvas.save();
            canvas.clipRect(0f, 0f, fillWidth, height);
            canvas.drawRoundRect(bounds, radius, radius, fillPaint);

            float glintX = shimmerPosition * width;
            sparklePaint.setShader(new LinearGradient(
                    glintX - dp(22), 0f, glintX + dp(22), 0f,
                    new int[]{Color.TRANSPARENT, Color.argb(205, 255, 255, 255), Color.TRANSPARENT},
                    null,
                    Shader.TileMode.CLAMP
            ));
            canvas.drawRect(glintX - dp(22), 0f, glintX + dp(22), height, sparklePaint);
            sparklePaint.setShader(null);
            sparklePaint.setColor(Color.argb(220, 255, 255, 255));
            float starX = Math.min(Math.max(dp(5), glintX), fillWidth - dp(4));
            if (starX > 0f) {
                canvas.drawCircle(starX, height * 0.32f, dp(1), sparklePaint);
                canvas.drawCircle(Math.max(dp(4), starX - dp(7)), height * 0.70f, dp(1) * 0.7f, sparklePaint);
            }
            canvas.restoreToCount(save);
            fillPaint.setShader(null);
        }

        @Override
        protected void onDetachedFromWindow() {
            if (animator != null) animator.cancel();
            super.onDetachedFromWindow();
        }
    }

    /** The destination card mirrors the resting card, avoiding a visual jump after a swipe. */
    private View reviewPreviewCard(MemoryCard memoryCard) {
        LinearLayout preview = card();
        preview.setGravity(Gravity.CENTER_HORIZONTAL);
        preview.setPadding(dp(22), dp(28), dp(22), dp(24));
        TextView kind = label("word".equals(memoryCard.type) ? "WORD" : "SENTENCE", 10, BLUE, Typeface.BOLD);
        preview.addView(kind);
        TextView front = label(memoryCard.front, "word".equals(memoryCard.type) ? 30 : 23, INK, Typeface.BOLD);
        front.setGravity(Gravity.CENTER);
        front.setPadding(0, dp(15), 0, 0);
        preview.addView(front, fullWrap());
        if (!memoryCard.phonetic.isEmpty()) {
            TextView ipa = label(IpaFormatter.formatIpaForDisplay(memoryCard.phonetic), 18, LILAC, Typeface.NORMAL);
            ipa.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            ipa.setLetterSpacing(0.018f);
            ipa.setIncludeFontPadding(false);
            ipa.setGravity(Gravity.CENTER);
            ipa.setPadding(0, dp(8), 0, 0);
            preview.addView(ipa, fullWrap());
        }
        preview.addView(linkButton("朗读"));
        TextView hint = label("左滑轻松并进入下一张 · 右滑返回上一张", 10, MUTED, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, 0);
        preview.addView(hint);
        Button reveal = primaryButton("显示答案");
        LinearLayout.LayoutParams revealParams = fullHeight(50);
        revealParams.topMargin = dp(20);
        preview.addView(reveal, revealParams);
        return preview;
    }

    private void revealReviewAnswer(Button reveal, View answer, View ratings, TextView swipeHint, int direction) {
        if (answer.getVisibility() == View.VISIBLE) return;
        reveal.setVisibility(View.GONE);
        answer.setVisibility(View.VISIBLE);
        ratings.setVisibility(View.VISIBLE);
        swipeHint.setText("左滑默认标记为轻松 · 右滑返回上一张 · 也可在下方选择更准确的结果");
        answer.setAlpha(0f);
        answer.setTranslationX(dp(-18 * direction));
        ratings.setAlpha(0f);
        ratings.setTranslationX(dp(-18 * direction));
        answer.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(new DecelerateInterpolator()).start();
        ratings.animate().alpha(1f).translationX(0f).setStartDelay(70).setDuration(280).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void addRating(LinearLayout parent, LinearLayout reviewCard, MemoryCard memoryCard, String title, String rating, int color) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTextColor(color);
        button.setBackground(rounded(CARD_RAISED, 11, LINE));
        applyFlatButtonBehavior(button);
        button.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            advanceReview(memoryCard, rating, reviewCard);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMarginEnd(dp(5));
        parent.addView(button, params);
    }

    private void advanceReview(MemoryCard memoryCard, String rating, View reviewCard) {
        if (reviewAdvancing) return;
        reviewAdvancing = true;
        long reviewedAt = reviewPracticeMode
                ? Math.max(System.currentTimeMillis(), reviewSessionStartedAt + 1)
                : System.currentTimeMillis();
        reviewProgressAnimationStart = reviewProgressValue();
        ReviewBaseline baseline = reviewBaselines.get(memoryCard.id);
        if (baseline == null) {
            baseline = memoryCard.reviewBaseline();
            reviewBaselines.put(memoryCard.id, baseline);
        }
        db.reviewFromBaseline(memoryCard.id, rating, reviewedAt, baseline, reviewSessionId);
        reviewRatings.put(memoryCard.id, rating);
        revealedReviewCardId = -1;
        animateReviewProgress = true;
        ReviewNotifications.scheduleNext(this);
        reviewCard.setEnabled(false);
        moveToNextUnratedCard();
        reviewCardSwitching = true;
        ReviewSwipeStage stage = reviewSwipeStage;
        if (stage != null && stage.activeCard() == reviewCard) {
            // Rating a card continues through the same leftward hand-off as a left swipe.
            animateReviewCardTransition(stage, -1, this::showReview);
        } else {
            animateReviewCardExit(reviewCard, -1, this::showReview);
        }
    }

    /** Holds the active card and its two destination previews during a swipe. */
    private final class ReviewSwipeStage extends FrameLayout {
        private View activeCard;
        private View leftCard;
        private View rightCard;

        ReviewSwipeStage() {
            super(MainActivity.this);
            setClipChildren(false);
            setClipToPadding(false);
        }

        void setCards(View activeCard, View leftCard, View rightCard) {
            this.activeCard = activeCard;
            this.leftCard = leftCard;
            this.rightCard = rightCard;
            FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (leftCard != null) {
                addView(leftCard, new FrameLayout.LayoutParams(layout));
                preparePreview(leftCard, -1);
            }
            if (rightCard != null) {
                addView(rightCard, new FrameLayout.LayoutParams(layout));
                preparePreview(rightCard, 1);
            }
            addView(activeCard, layout);
            activeCard.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            activeCard.post(() -> setLowerPivot(activeCard));
        }

        View activeCard() {
            return activeCard;
        }

        View destinationCard(int direction) {
            return direction < 0 ? leftCard : rightCard;
        }

        private void preparePreview(View card, int direction) {
            card.setAlpha(0f);
            card.setScaleX(1f);
            card.setScaleY(1f);
            card.setTranslationX(dp(direction * 28));
        }

        private void updateDrag(float dx) {
            if (activeCard == null) return;
            float width = Math.max(1f, getWidth());
            float progress = Math.min(1f, Math.abs(dx) / (width * 0.62f));
            setLowerPivot(activeCard);
            activeCard.setTranslationX(dx);
            float tilt = Math.min(30f, Math.abs(dx) / width * 48f);
            activeCard.setRotation(Math.signum(dx) * tilt);
            float activeScale = 1f - progress * 0.025f;
            activeCard.setScaleX(activeScale);
            activeCard.setScaleY(activeScale);
            updatePreview(dx < 0f ? leftCard : rightCard, dx < 0f ? -1 : 1, progress);
            updatePreview(dx < 0f ? rightCard : leftCard, dx < 0f ? 1 : -1, 0f);
        }

        private void setLowerPivot(View card) {
            card.setPivotX(card.getWidth() * 0.5f);
            card.setPivotY(card.getHeight() * 0.72f);
        }

        private void updatePreview(View card, int direction, float progress) {
            if (card == null) return;
            card.setAlpha(Math.min(1f, 0.10f + progress * 0.90f));
            card.setScaleX(1f);
            card.setScaleY(1f);
            card.setTranslationX(dp(direction * 28) * (1f - progress));
        }

        void resetVisuals() {
            if (activeCard != null) {
                activeCard.animate().translationX(0f).translationY(0f).rotation(0f)
                        .scaleX(1f).scaleY(1f).alpha(1f).setDuration(220)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(0.75f)).start();
            }
            resetPreview(leftCard, -1);
            resetPreview(rightCard, 1);
        }

        private void resetPreview(View card, int direction) {
            if (card == null) return;
            card.animate().alpha(0f).scaleX(1f).scaleY(1f).translationX(dp(direction * 28))
                    .setDuration(150).setInterpolator(new DecelerateInterpolator()).start();
        }

    }

    /** Captures a horizontal tendency anywhere on the review page, including its empty space. */
    private final class ReviewScreenSwipeSurface extends FrameLayout {
        private float downX;
        private boolean horizontalGesture;
        private final int touchSlop;

        ReviewScreenSwipeSurface() {
            super(MainActivity.this);
            touchSlop = android.view.ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    horizontalGesture = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    // Deliberately ignore vertical displacement: any left/right tendency owns
                    // the interaction, even when the hand naturally travels on a diagonal.
                    if (Math.abs(event.getX() - downX) >= Math.max(1f, Math.min(touchSlop, dp(2)))) {
                        horizontalGesture = true;
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return horizontalGesture;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            ReviewSwipeStage stage = reviewSwipeStage;
            if (stage == null) return super.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (horizontalGesture) {
                        stage.updateDrag(event.getX() - downX);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (horizontalGesture) {
                        float dx = event.getX() - downX;
                        horizontalGesture = false;
                        float triggerDistance = Math.max(dp(18), getWidth() * 0.05f);
                        if (Math.abs(dx) >= triggerDistance) {
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            navigateReview(dx > 0 ? 1 : -1, stage);
                        } else {
                            stage.resetVisuals();
                        }
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    if (horizontalGesture) {
                        horizontalGesture = false;
                        stage.resetVisuals();
                        return true;
                    }
                    break;
            }
            return horizontalGesture || super.onTouchEvent(event);
        }
    }

    private void navigateReview(int direction, ReviewSwipeStage stage) {
        if (reviewAdvancing) return;
        // Physical left records an easy recall; right is a non-destructive look back.
        if (direction > 0) {
            int previousIndex = reviewCardIndex - 1;
            if (previousIndex < 0) {
                nudgeReviewCard(stage, direction);
                return;
            }
            reviewAdvancing = true;
            reviewProgressAnimationStart = reviewProgressValue();
            animateReviewProgress = true;
            reviewCardIndex = previousIndex;
            revealedReviewCardId = -1;
            reviewCardSwitching = true;
            animateReviewCardTransition(stage, direction, this::showReview);
            return;
        }

        MemoryCard memoryCard = reviewCards.get(reviewCardIndex);
        reviewAdvancing = true;
        long reviewedAt = reviewPracticeMode
                ? Math.max(System.currentTimeMillis(), reviewSessionStartedAt + 1)
                : System.currentTimeMillis();
        reviewProgressAnimationStart = reviewProgressValue();
        ReviewBaseline baseline = reviewBaselines.get(memoryCard.id);
        if (baseline == null) {
            baseline = memoryCard.reviewBaseline();
            reviewBaselines.put(memoryCard.id, baseline);
        }
        db.reviewFromBaseline(memoryCard.id, "easy", reviewedAt, baseline, reviewSessionId);
        reviewRatings.put(memoryCard.id, "easy");
        revealedReviewCardId = -1;
        animateReviewProgress = true;
        ReviewNotifications.scheduleNext(this);
        stage.activeCard().setEnabled(false);
        moveToNextUnratedCard();
        if (reviewCardIndex >= reviewCards.size()) {
            reviewCardSwitching = true;
            animateReviewCardExit(stage.activeCard(), direction, this::showReview);
            return;
        }
        reviewCardSwitching = true;
        animateReviewCardTransition(stage, direction, this::showReview);
    }

    private MemoryCard nextUnseenReviewCard() {
        List<Long> seenIds = new ArrayList<>();
        for (MemoryCard card : reviewCards) seenIds.add(card.id);
        return reviewPracticeMode
                ? db.nextPracticeExcluding(reviewSessionStartedAt, reviewWordbookId, seenIds)
                : db.nextDueExcluding(System.currentTimeMillis(), reviewWordbookId, seenIds);
    }

    private void moveToNextUnratedCard() {
        for (int index = reviewCardIndex + 1; index < reviewCards.size(); index++) {
            if (!reviewRatings.containsKey(reviewCards.get(index).id)) {
                reviewCardIndex = index;
                return;
            }
        }
        MemoryCard next = nextUnseenReviewCard();
        if (next != null) {
            reviewCards.add(next);
            reviewCardIndex = reviewCards.size() - 1;
            return;
        }
        for (int index = 0; index < reviewCardIndex; index++) {
            if (!reviewRatings.containsKey(reviewCards.get(index).id)) {
                reviewCardIndex = index;
                return;
            }
        }
        reviewCardIndex = reviewCards.size();
    }

    private String ratingLabel(String rating) {
        if ("again".equals(rating)) return "忘记";
        if ("hard".equals(rating)) return "模糊";
        if ("easy".equals(rating)) return "轻松";
        return "记得";
    }

    private void animateReviewCardExit(View card, int direction, Runnable onEnd) {
        float distance = Math.max(dp(120), card.getWidth() + dp(36));
        card.animate().translationX(direction * distance).alpha(0f).rotation(direction * 30f)
                .setDuration(250).setInterpolator(new DecelerateInterpolator())
                .withEndAction(onEnd).start();
    }

    /**
     * Finishes a gesture with the already-visible destination card.  Rebuilding the review page
     * happens only after both layers are at their resting geometry, so there is no pop-in frame.
     */
    private void animateReviewCardTransition(ReviewSwipeStage stage, int direction, Runnable onEnd) {
        View outgoing = stage.activeCard();
        View incoming = stage.destinationCard(direction);
        float distance = Math.max(dp(120), outgoing.getWidth() + dp(36));
        if (incoming == null) {
            animateReviewCardExit(outgoing, direction, onEnd);
            return;
        }
        incoming.animate().translationX(0f).translationY(0f).rotation(0f)
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(250).setInterpolator(new DecelerateInterpolator()).start();
        outgoing.animate().translationX(direction * distance).alpha(0f).rotation(direction * 30f)
                .setDuration(250).setInterpolator(new DecelerateInterpolator())
                .withEndAction(onEnd).start();
    }

    private void nudgeReviewCard(ReviewSwipeStage stage, int direction) {
        View card = stage.activeCard();
        float nudge = direction * dp(20);
        card.animate().translationX(nudge).setDuration(90).setInterpolator(new DecelerateInterpolator())
                .withEndAction(stage::resetVisuals).start();
    }

    private void showConnect() {
        selectPage("connect");
        SyncConfig config = secureStore.load();
        LinearLayout body = pageBody();
        body.addView(eyebrow("ENCRYPTED SYNC"));
        body.addView(title("连接桌面翻译器", 27));
        body.addView(subtitle("只需配对一次。以后翻译完成即自动上传，手机端自动接收。创建空间无需注册码。"));

        if (config == null) {
            LinearLayout form = card();
            TextView intro = label("创建你的加密同步空间", 15, INK, Typeface.BOLD);
            form.addView(intro);
            EditText server = input("https://memory.example.com", false);
            form.addView(fieldLabel("服务器地址"));
            form.addView(server, fullHeight(50));
            Button register = primaryButton("创建加密设备并开启自动接收");
            LinearLayout.LayoutParams buttonParams = fullHeight(52);
            buttonParams.topMargin = dp(18);
            form.addView(register, buttonParams);
            TextView status = label("", 11, MUTED, Typeface.NORMAL);
            status.setPadding(0, dp(10), 0, 0);
            form.addView(status);
            register.setOnClickListener(view -> {
                register.setEnabled(false);
                status.setText("正在创建端到端加密通道…");
                io.execute(() -> {
                    try {
                        SyncConfig created = CloudApi.register(server.getText().toString());
                        secureStore.save(created);
                        runOnUiThread(() -> {
                            copyPairing(created);
                            CloudSyncService.start(this);
                            syncMessage = "加密通道已连接";
                            showConnect();
                            Toast.makeText(this, "桌面配对信息已复制", Toast.LENGTH_LONG).show();
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> {
                            register.setEnabled(true);
                            status.setTextColor(Color.rgb(255, 125, 106));
                            status.setText(error.getMessage());
                        });
                    }
                });
            });
            LinearLayout.LayoutParams formParams = fullWrap();
            formParams.topMargin = dp(20);
            body.addView(form, formParams);
        } else {
            LinearLayout connected = card();
            connected.addView(statusHeader("自动接收", "已开启", MINT));
            TextView server = label(config.serverUrl, 12, INK, Typeface.BOLD);
            server.setPadding(0, dp(14), 0, 0);
            connected.addView(server);
            connected.addView(label("设备 " + config.deviceId.substring(0, 8) + "…", 10, MUTED, Typeface.NORMAL));
            TextView state = label(syncMessage, 11, BLUE, Typeface.NORMAL);
            state.setPadding(0, dp(10), 0, 0);
            connectSyncMessageView = state;
            homeSyncMessageView = null;
            connected.addView(state);
            LinearLayout peers = vertical(0);
            peers.setPadding(0, dp(12), 0, 0);
            peers.addView(emptyPresence("正在读取设备状态…"));
            connected.addView(peers);
            loadDesktopPresence(peers, config);

            Button copy = primaryButton("复制桌面端配对信息");
            copy.setOnClickListener(view -> {
                copyPairing(config);
                Toast.makeText(this, "已复制；粘贴到桌面翻译器设置中", Toast.LENGTH_LONG).show();
            });
            LinearLayout.LayoutParams copyParams = fullHeight(52);
            copyParams.topMargin = dp(18);
            connected.addView(copy, copyParams);

            Button unlink = linkButton("解除本机连接");
            unlink.setTextColor(Color.rgb(255, 125, 106));
            unlink.setOnClickListener(view -> {
                CloudSyncService.stop(this);
                secureStore.clear();
                syncMessage = "等待自动接收";
                showConnect();
            });
            connected.addView(unlink);
            LinearLayout.LayoutParams connectedParams = fullWrap();
            connectedParams.topMargin = dp(20);
            body.addView(connected, connectedParams);
        }

        LinearLayout privacy = card();
        privacy.addView(label("零知识中继", 13, INK, Typeface.BOLD));
        privacy.addView(label(
                "桌面翻译和自定义词库都会端到端加密。服务器只保存密文版本，不会看到单词、译文和技术语境。",
                11,
                MUTED,
                Typeface.NORMAL
        ));
        LinearLayout.LayoutParams privacyParams = fullWrap();
        privacyParams.topMargin = dp(12);
        body.addView(privacy, privacyParams);

        LinearLayout updates = card();
        updates.addView(label("软件更新 · v" + currentVersionName(), 13, INK, Typeface.BOLD));
        updates.addView(label(
                "每天自动检查四次；新版本会在校验包名、版本、哈希和签名后下载并通知安装。",
                11,
                MUTED,
                Typeface.NORMAL
        ));
        Button checkUpdate = linkButton("立即检查更新");
        checkUpdate.setOnClickListener(view -> {
            Toast.makeText(this, "正在检查安全更新…", Toast.LENGTH_SHORT).show();
            AppUpdateManager.checkAsync(this, true);
        });
        updates.addView(checkUpdate);
        LinearLayout.LayoutParams updateParams = fullWrap();
        updateParams.topMargin = dp(12);
        body.addView(updates, updateParams);
        setPage(body);
    }

    private String currentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "—";
        }
    }

    private void loadDesktopPresence(LinearLayout target, SyncConfig config) {
        io.execute(() -> {
            try {
                CloudApi.DesktopPresence presence = CloudApi.desktopPresence(config);
                runOnUiThread(() -> {
                    if (target.isAttachedToWindow()) {
                        renderDesktopPresence(target, presence);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (target.isAttachedToWindow()) {
                        target.removeAllViews();
                        target.addView(emptyPresence("暂时无法读取设备状态，稍后会自动重试"));
                    }
                });
            }
        });
    }

    private void renderDesktopPresence(LinearLayout target, CloudApi.DesktopPresence presence) {
        target.removeAllViews();
        if (presence.clients.isEmpty()) {
            target.addView(emptyPresence("还没有电脑上报状态；打开桌面翻译器后会自动出现"));
            return;
        }
        LinearLayout summary = new LinearLayout(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.addView(label(presence.clients.size() + " 台电脑", 10, MUTED, Typeface.BOLD), weighted());
        summary.addView(statusPill(presence.online + " 台在线", presence.online > 0 ? MINT : MUTED));
        target.addView(summary);
        for (CloudApi.DesktopClient client : presence.clients) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            applyInsetSurfaceStyle(row, INSET_SURFACE_CORNER_RADIUS_DP);
            View dot = new View(this);
            dot.setBackground(rounded(client.online ? MINT : MUTED, 4));
            row.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
            LinearLayout copy = vertical(0);
            copy.setPadding(dp(10), 0, dp(8), 0);
            copy.addView(label(client.name, 11, INK, Typeface.BOLD));
            String metadata = platformLabel(client.platform)
                    + (client.appVersion.isEmpty() ? "" : "  ·  v" + client.appVersion);
            copy.addView(label(metadata, 9, MUTED, Typeface.NORMAL));
            row.addView(copy, weighted());
            row.addView(label(relativePresence(client, presence.serverTime), 9,
                    client.online ? MINT : MUTED, Typeface.BOLD));
            LinearLayout.LayoutParams params = fullWrap();
            params.topMargin = dp(8);
            target.addView(row, params);
        }
    }

    private String platformLabel(String platform) {
        if ("darwin".equals(platform)) return "Mac";
        if ("win32".equals(platform)) return "Windows";
        if ("linux".equals(platform)) return "Linux";
        return "电脑";
    }

    private String relativePresence(CloudApi.DesktopClient client, long serverTime) {
        if (client.online) return "在线";
        long minutes = Math.max(1, (serverTime - client.lastSeenAt) / 60_000);
        if (minutes < 60) return minutes + " 分钟前";
        long hours = minutes / 60;
        if (hours < 24) return hours + " 小时前";
        return Math.min(99, hours / 24) + " 天前";
    }

    private void copyPairing(SyncConfig config) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("LinguaBridge desktop pairing", config.pairingUri()));
    }

    private void speak(MemoryCard memoryCard) {
        if (textToSpeech == null) return;
        Locale locale = memoryCard.frontLanguage.startsWith("zh") ? Locale.SIMPLIFIED_CHINESE : Locale.US;
        textToSpeech.setLanguage(locale);
        textToSpeech.speak(memoryCard.front, TextToSpeech.QUEUE_FLUSH, null, "memory-card");
    }

    private void updateSyncMessageViews() {
        if (homeSyncMessageView != null && homeSyncMessageView.isAttachedToWindow()) {
            homeSyncMessageView.setText(syncMessage);
        }
        if (connectSyncMessageView != null && connectSyncMessageView.isAttachedToWindow()) {
            connectSyncMessageView.setText(syncMessage);
        }
    }

    private void setPage(LinearLayout body) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body);
        content.removeAllViews();
        FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        );
        if ("review".equals(currentPage) && reviewSwipeStage != null) {
            ReviewScreenSwipeSurface surface = new ReviewScreenSwipeSurface();
            surface.addView(scroll, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ));
            content.addView(surface, pageParams);
        } else {
            content.addView(scroll, pageParams);
        }
        boolean skipAnimation = suppressNextPageAnimation;
        suppressNextPageAnimation = false;
        if (themeTransitioning || skipAnimation) return;
        body.setAlpha(0f);
        body.setTranslationX(0f);
        body.setTranslationY(dp(12));
        AnimatorSet entrance = new AnimatorSet();
        entrance.playTogether(
                ObjectAnimator.ofFloat(body, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(body, View.TRANSLATION_X, body.getTranslationX(), 0f),
                ObjectAnimator.ofFloat(body, View.TRANSLATION_Y, body.getTranslationY(), 0f)
        );
        entrance.setDuration(320);
        entrance.setInterpolator(new DecelerateInterpolator());
        entrance.start();
    }

    private LinearLayout pageBody() {
        LinearLayout body = vertical(0);
        body.setPadding(dp(20), dp(12), dp(20), dp(28));
        return body;
    }

    private LinearLayout card() {
        LinearLayout card = vertical(3);
        card.setPadding(dp(18), dp(17), dp(18), dp(17));
        applyCardStyle(card);
        return card;
    }

    private View statCard(String title, int value, String hint) {
        LinearLayout card = vertical(0);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        applyFlatSurfaceStyle(card, CARD, STAT_CARD_CORNER_RADIUS_DP);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label(title, 10, MUTED, Typeface.BOLD), weighted());
        View accent = new View(this);
        accent.setBackground(rounded("待复习".equals(title) ? CORAL : BLUE, 3));
        heading.addView(accent, new LinearLayout.LayoutParams(dp(6), dp(6)));
        card.addView(heading);
        TextView number = label(String.valueOf(value), 28, INK, Typeface.BOLD);
        number.setPadding(0, dp(2), 0, 0);
        card.addView(number);
        card.addView(label(hint, 9, MUTED, Typeface.NORMAL));
        return card;
    }

    private LinearLayout statusHeader(String title, String status, int color) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(rounded(color, 4));
        header.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView heading = label(title, 12, INK, Typeface.BOLD);
        heading.setPadding(dp(9), 0, 0, 0);
        header.addView(heading, weighted());
        header.addView(statusPill(status, color));
        return header;
    }

    private TextView statusPill(String text, int color) {
        TextView pill = label(text, 9, color, Typeface.BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(10), dp(5), dp(10), dp(5));
        pill.setBackground(rounded(withAlpha(color, darkMode ? 36 : 22), PILL_CORNER_RADIUS_DP,
                withAlpha(color, darkMode ? 86 : 54)));
        clearElevation(pill);
        return pill;
    }

    private TextView emptyPresence(String text) {
        TextView empty = label(text, 10, MUTED, Typeface.NORMAL);
        empty.setPadding(dp(12), dp(10), dp(12), dp(10));
        applyInsetSurfaceStyle(empty, 13);
        return empty;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private TextView eyebrow(String text) {
        TextView view = label(text, 10, BLUE, Typeface.BOLD);
        view.setLetterSpacing(0.14f);
        return view;
    }

    private TextView title(String text, int size) {
        TextView view = label(text, size, INK, Typeface.BOLD);
        view.setPadding(0, dp(4), 0, 0);
        return view;
    }

    private TextView subtitle(String text) {
        TextView view = label(text, 12, MUTED, Typeface.NORMAL);
        view.setPadding(0, dp(7), 0, 0);
        view.setLineSpacing(0, 1.15f);
        return view;
    }

    private TextView sectionLabel(String text) {
        TextView view = label(text, 10, BLUE, Typeface.BOLD);
        view.setPadding(0, dp(14), 0, dp(3));
        return view;
    }

    private TextView fieldLabel(String text) {
        TextView view = label(text, 10, MUTED, Typeface.BOLD);
        view.setPadding(0, dp(14), 0, dp(6));
        return view;
    }

    private EditText input(String hint, boolean password) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextSize(13);
        input.setSingleLine(true);
        input.setPadding(dp(13), 0, dp(13), 0);
        input.setTextColor(INK);
        input.setHintTextColor(darkMode ? Color.rgb(105, 96, 119) : Color.rgb(145, 136, 153));
        input.setBackground(rounded(CARD_RAISED, 12, LINE));
        input.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return input;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(gradient(new int[]{Color.rgb(123, 69, 203), Color.rgb(181, 126, 255)}, 14));
        applyFlatButtonBehavior(button);
        return button;
    }

    private Button linkButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTextColor(BLUE);
        button.setBackgroundColor(Color.TRANSPARENT);
        applyFlatButtonBehavior(button);
        return button;
    }

    private int surfaceStrokeColor() {
        return LINE;
    }

    private void applyCardStyle(View view) {
        applyFlatSurfaceStyle(view, CARD, CARD_CORNER_RADIUS_DP);
    }

    private void applyInsetSurfaceStyle(View view, int cornerRadius) {
        applyFlatSurfaceStyle(view, CARD_RAISED, cornerRadius);
    }

    private void applyFlatSurfaceStyle(View view, int color, int cornerRadius) {
        view.setBackground(rounded(color, cornerRadius, surfaceStrokeColor()));
        clearElevation(view);
    }

    private void applyFlatButtonBehavior(Button button) {
        clearElevation(button);
    }

    private void clearElevation(View view) {
        view.setStateListAnimator(null);
        view.setElevation(0f);
    }

    private TextView label(String text, int size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setLineSpacing(0, 1.1f);
        return view;
    }

    private LinearLayout vertical(int spacing) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        if (spacing > 0) layout.setDividerPadding(dp(spacing));
        return layout;
    }

    private View space(int width) {
        Space space = new Space(this);
        space.setMinimumWidth(width);
        return space;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private LinearLayout.LayoutParams fullWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams fullHeight(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height));
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private GradientDrawable rounded(int color, int radius, int stroke) {
        GradientDrawable drawable = rounded(color, radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private GradientDrawable gradient(int[] colors, int radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                colors
        );
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private GradientDrawable gradient(int[] colors, int radius, int stroke) {
        GradientDrawable drawable = gradient(colors, radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private int legacyStatusBarInset(WindowInsets insets) {
        return insets.getSystemWindowInsetTop();
    }

    private void requestNotificationPermission() {
        if (
                Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerSyncReceiver() {
        IntentFilter filter = new IntentFilter(CloudSyncService.ACTION_SYNC_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(syncReceiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(syncReceiver, filter);
    }
}
