package com.linguabridge.memory;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
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

public final class MainActivity extends Activity implements TextToSpeech.OnInitListener {
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
    private int reviewSessionTotal;
    private int reviewProgressAnimationStart;
    private boolean animateReviewProgress;
    private ReviewSwipeStage reviewSwipeStage;
    /** Cards encountered in this session. Keeping this small, growing history makes swipe-back predictable. */
    private final List<MemoryCard> reviewCards = new ArrayList<>();
    private final Map<Long, String> reviewRatings = new HashMap<>();
    private int reviewCardIndex = -1;

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
            libraryWordbookId = id; libraryWordbookName = name; libraryOffset = 0; showLibrary();
        });
        secureStore = new SecureStore(this);
        textToSpeech = new TextToSpeech(this, this);
        ReviewNotifications.createChannels(this);
        requestNotificationPermission();
        rootHost = new FrameLayout(this);
        setContentView(rootHost);
        buildShell();
        registerSyncReceiver();
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
        LinearLayout body = pageBody();
        long now = System.currentTimeMillis();
        MemoryDb.Stats stats = db.stats(now);
        List<Wordbook> wordbooks = db.wordbooks(now);
        body.addView(eyebrow("LIBRARY"));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL | Gravity.BOTTOM);
        heading.addView(title("我的词库", 29), weighted());
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
            wordbookList.addView(wordbookSelector(
                    book.name,
                    book.id == 1 ? "桌面翻译自动接收" : "自定义词库 · 本地保存",
                    book.total, book.due, book.fresh,
                    libraryWordbookId == book.id,
                    book.id == 1 ? "桌" : "词",
                    book.id == 1 ? CORAL : LILAC,
                    () -> selectLibraryWordbook(book.id, book.name)
            ), selectorParams);
        }
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
            ipa.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            row.addView(ipa);
        }
        TextView back = label(memoryCard.back, 13, MUTED, Typeface.NORMAL);
        back.setPadding(0, dp(7), 0, 0);
        row.addView(back);
        if (!memoryCard.technicalNotes.isEmpty()) {
            TextView note = label(memoryCard.technicalNotes.get(0), 11,
                    darkMode ? Color.rgb(190, 177, 208) : Color.rgb(91, 78, 103), Typeface.NORMAL);
            note.setPadding(0, dp(9), 0, 0);
            row.addView(note);
        }
        if (!memoryCard.category.isEmpty()) row.addView(label(memoryCard.category, 11, BLUE, Typeface.NORMAL));
        if (!memoryCard.context.isEmpty()) row.addView(label(memoryCard.context, 12, MUTED, Typeface.NORMAL));
        return row;
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
        reviewCardIndex = -1;
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
            ipa.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            ipa.setGravity(Gravity.CENTER);
            ipa.setPadding(0, dp(8), 0, 0);
            reviewCard.addView(ipa, fullWrap());
        }
        Button speak = linkButton("朗读");
        speak.setOnClickListener(view -> speak(memoryCard));
        reviewCard.addView(speak);

        TextView swipeHint = label(recordedThisSession
                ? "本轮已记录"
                : "左滑轻松并进入下一张 · 右滑返回上一张", 10, MUTED, Typeface.NORMAL);
        swipeHint.setGravity(Gravity.CENTER);
        swipeHint.setPadding(0, dp(8), 0, 0);
        reviewCard.addView(swipeHint);

        Button reveal = primaryButton("显示答案");
        LinearLayout.LayoutParams revealParams = fullHeight(50);
        revealParams.topMargin = dp(20);
        reviewCard.addView(reveal, revealParams);

        LinearLayout answer = vertical(10);
        answer.setVisibility(recordedThisSession ? View.VISIBLE : View.GONE);
        answer.setPadding(0, dp(22), 0, 0);
        answer.addView(label("答案", 10, MUTED, Typeface.BOLD));
        answer.addView(label(memoryCard.back, 20, INK, Typeface.BOLD));
        if (!memoryCard.category.isEmpty()) answer.addView(label(memoryCard.category, 12, BLUE, Typeface.NORMAL));
        if (!memoryCard.context.isEmpty()) {
            answer.addView(sectionLabel("原句语境"));
            answer.addView(label(memoryCard.context, 12, MUTED, Typeface.NORMAL));
        }
        if (!memoryCard.technicalNotes.isEmpty()) {
            answer.addView(sectionLabel("技术关联"));
            for (String note : memoryCard.technicalNotes) {
                answer.addView(label("• " + note, 12,
                        darkMode ? Color.rgb(202, 190, 219) : Color.rgb(88, 75, 100), Typeface.NORMAL));
            }
        }
        reviewCard.addView(answer, fullWrap());

        LinearLayout ratings = new LinearLayout(this);
        ratings.setVisibility(View.GONE);
        ratings.setPadding(0, dp(16), 0, 0);
        addRating(ratings, reviewCard, memoryCard, "忘记", "again", Color.rgb(197, 67, 55));
        addRating(ratings, reviewCard, memoryCard, "模糊", "hard", Color.rgb(180, 119, 28));
        addRating(ratings, reviewCard, memoryCard, "记得", "good", BLUE);
        addRating(ratings, reviewCard, memoryCard, "轻松", "easy", Color.rgb(40, 132, 93));
        reviewCard.addView(ratings, fullWrap());
        if (recordedThisSession) {
            TextView recorded = label("已记录：" + ratingLabel(savedRating), 12, BLUE, Typeface.BOLD);
            recorded.setGravity(Gravity.CENTER);
            recorded.setPadding(0, dp(16), 0, 0);
            reviewCard.addView(recorded, fullWrap());
            reveal.setVisibility(View.GONE);
        }
        reveal.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
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
        return Math.min(Math.max(0, reviewCardIndex), reviewSessionTotal);
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
            ipa.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            ipa.setGravity(Gravity.CENTER);
            ipa.setPadding(0, dp(8), 0, 0);
            preview.addView(ipa, fullWrap());
        }
        preview.addView(linkButton("朗读"));
        String savedRating = reviewRatings.get(memoryCard.id);
        TextView hint = label(savedRating == null
                ? "左滑轻松并进入下一张 · 右滑返回上一张"
                : "本轮已记录 · 左滑轻松下一张 · 右滑上一张", 10, MUTED, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, 0);
        preview.addView(hint);
        if (savedRating == null) {
            Button reveal = primaryButton("显示答案");
            LinearLayout.LayoutParams revealParams = fullHeight(50);
            revealParams.topMargin = dp(20);
            preview.addView(reveal, revealParams);
        } else {
            LinearLayout answer = vertical(10);
            answer.setPadding(0, dp(22), 0, 0);
            answer.addView(label("答案", 10, MUTED, Typeface.BOLD));
            answer.addView(label(memoryCard.back, 20, INK, Typeface.BOLD));
            if (!memoryCard.category.isEmpty()) answer.addView(label(memoryCard.category, 12, BLUE, Typeface.NORMAL));
            if (!memoryCard.context.isEmpty()) {
                answer.addView(sectionLabel("原句语境"));
                answer.addView(label(memoryCard.context, 12, MUTED, Typeface.NORMAL));
            }
            if (!memoryCard.technicalNotes.isEmpty()) {
                answer.addView(sectionLabel("技术关联"));
                for (String note : memoryCard.technicalNotes) {
                    answer.addView(label("• " + note, 12,
                            darkMode ? Color.rgb(202, 190, 219) : Color.rgb(88, 75, 100), Typeface.NORMAL));
                }
            }
            preview.addView(answer, fullWrap());
            TextView recorded = label("已记录：" + ratingLabel(savedRating), 12, BLUE, Typeface.BOLD);
            recorded.setGravity(Gravity.CENTER);
            recorded.setPadding(0, dp(16), 0, 0);
            preview.addView(recorded, fullWrap());
        }
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
        db.review(memoryCard.id, rating, reviewedAt);
        reviewRatings.put(memoryCard.id, rating);
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
        db.review(memoryCard.id, "easy", reviewedAt);
        reviewRatings.put(memoryCard.id, "easy");
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
