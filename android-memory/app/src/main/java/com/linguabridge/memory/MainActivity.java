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
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
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

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements TextToSpeech.OnInitListener {
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
    private SecureStore secureStore;
    private FrameLayout content;
    private FrameLayout rootHost;
    private LinearLayout navigation;
    private View rootLayout;
    private TextToSpeech textToSpeech;
    private String currentPage = "home";
    private String syncMessage = "等待自动接收";
    private boolean themeTransitioning;

    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            syncMessage = intent.getStringExtra("message");
            if ("home".equals(currentPage)) showHome();
            if ("connect".equals(currentPage)) showConnect();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyPalette();
        db = new MemoryDb(this);
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
        else if (getIntent().getBooleanExtra("openReview", false)) showReview();
        else showHome();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (handlePairingIntent(intent)) showConnect();
        else if (intent.getBooleanExtra("openReview", false)) showReview();
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
        theme.setStateListAnimator(null);
        theme.setElevation(0);
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
        button.setStateListAnimator(null);
        button.setElevation(0);
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
        body.addView(subtitle("桌面翻译器会自动送来新内容；复习进度只保存在这台手机。"));

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
                stats.due == 0 ? "今天的记忆已经收好，去翻译一点新内容吧。" : "每次短暂回忆，都在把知识放进长期记忆。",
                10,
                MUTED,
                Typeface.NORMAL
        ));
        LinearLayout.LayoutParams rhythmParams = fullWrap();
        rhythmParams.bottomMargin = dp(16);
        body.addView(rhythm, rhythmParams);

        Button start = primaryButton(stats.due > 0 ? "开始复习 · " + stats.due : "今天已完成");
        start.setEnabled(stats.due > 0);
        start.setOnClickListener(view -> showReview());
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

    private void showLibrary() {
        selectPage("library");
        LinearLayout body = pageBody();
        MemoryDb.Stats stats = db.stats(System.currentTimeMillis());
        body.addView(eyebrow("LIBRARY"));
        body.addView(title("我的词库", 29));
        body.addView(subtitle("共 " + stats.total + " 条；技术术语会保留定义和原句语境。"));
        List<MemoryCard> cards = db.recent(60);
        if (cards.isEmpty()) {
            LinearLayout empty = card();
            TextView emptyTitle = label("还没有学习内容", 17, INK, Typeface.BOLD);
            emptyTitle.setGravity(Gravity.CENTER);
            TextView emptyText = label("连接桌面端后，翻译完成的单词和句子会自动出现。", 12, MUTED, Typeface.NORMAL);
            emptyText.setGravity(Gravity.CENTER);
            emptyText.setPadding(0, dp(8), 0, 0);
            empty.addView(emptyTitle);
            empty.addView(emptyText);
            LinearLayout.LayoutParams params = fullWrap();
            params.topMargin = dp(22);
            body.addView(empty, params);
        } else {
            for (MemoryCard memoryCard : cards) body.addView(libraryRow(memoryCard));
        }
        setPage(body);
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
            TextView ipa = label(memoryCard.phonetic, 15, LILAC, Typeface.NORMAL);
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
        return row;
    }

    private void showReview() {
        selectPage("home");
        currentPage = "review";
        MemoryCard memoryCard = db.nextDue(System.currentTimeMillis());
        if (memoryCard == null) {
            LinearLayout body = pageBody();
            body.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView check = label("✓", 36, Color.WHITE, Typeface.BOLD);
            check.setGravity(Gravity.CENTER);
            check.setBackground(gradient(new int[]{BLUE, CORAL}, 30));
            body.addView(check, new LinearLayout.LayoutParams(dp(60), dp(60)));
            TextView done = title("这一轮完成", 26);
            done.setPadding(0, dp(18), 0, 0);
            body.addView(done);
            body.addView(subtitle("下次复习时间已按你的反馈安排。"));
            Button back = primaryButton("返回今天");
            back.setOnClickListener(view -> showHome());
            LinearLayout.LayoutParams params = fullHeight(52);
            params.topMargin = dp(20);
            body.addView(back, params);
            setPage(body);
            return;
        }

        LinearLayout body = pageBody();
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(eyebrow("ACTIVE RECALL"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button finish = linkButton("结束本轮");
        finish.setOnClickListener(view -> showHome());
        toolbar.addView(finish);
        body.addView(toolbar);

        LinearLayout reviewCard = card();
        reviewCard.setGravity(Gravity.CENTER_HORIZONTAL);
        reviewCard.setPadding(dp(22), dp(28), dp(22), dp(24));
        TextView kind = label("word".equals(memoryCard.type) ? "WORD" : "SENTENCE", 10, BLUE, Typeface.BOLD);
        reviewCard.addView(kind);
        TextView front = label(memoryCard.front, "word".equals(memoryCard.type) ? 30 : 23, INK, Typeface.BOLD);
        front.setGravity(Gravity.CENTER);
        front.setPadding(0, dp(15), 0, 0);
        reviewCard.addView(front);
        if (!memoryCard.phonetic.isEmpty()) {
            TextView ipa = label(memoryCard.phonetic, 18, LILAC, Typeface.NORMAL);
            ipa.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            ipa.setPadding(0, dp(8), 0, 0);
            reviewCard.addView(ipa);
        }
        Button speak = linkButton("朗读");
        speak.setOnClickListener(view -> speak(memoryCard));
        reviewCard.addView(speak);

        Button reveal = primaryButton("显示答案");
        LinearLayout.LayoutParams revealParams = fullHeight(50);
        revealParams.topMargin = dp(20);
        reviewCard.addView(reveal, revealParams);

        LinearLayout answer = vertical(10);
        answer.setVisibility(View.GONE);
        answer.setPadding(0, dp(22), 0, 0);
        answer.addView(label("答案", 10, MUTED, Typeface.BOLD));
        answer.addView(label(memoryCard.back, 20, INK, Typeface.BOLD));
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
        addRating(ratings, memoryCard, "忘记", "again", Color.rgb(197, 67, 55));
        addRating(ratings, memoryCard, "模糊", "hard", Color.rgb(180, 119, 28));
        addRating(ratings, memoryCard, "记得", "good", BLUE);
        addRating(ratings, memoryCard, "轻松", "easy", Color.rgb(40, 132, 93));
        reviewCard.addView(ratings, fullWrap());
        reveal.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            reveal.setVisibility(View.GONE);
            answer.setVisibility(View.VISIBLE);
            ratings.setVisibility(View.VISIBLE);
            answer.setAlpha(0f);
            answer.setTranslationY(dp(12));
            ratings.setAlpha(0f);
            ratings.setTranslationY(dp(12));
            answer.animate().alpha(1f).translationY(0f).setDuration(260).start();
            ratings.animate().alpha(1f).translationY(0f).setStartDelay(80).setDuration(280).start();
        });

        LinearLayout.LayoutParams cardParams = fullWrap();
        cardParams.topMargin = dp(12);
        body.addView(reviewCard, cardParams);
        setPage(body);
    }

    private void addRating(LinearLayout parent, MemoryCard memoryCard, String title, String rating, int color) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTextColor(color);
        button.setBackground(rounded(CARD_RAISED, 11, LINE));
        button.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            db.review(memoryCard.id, rating, System.currentTimeMillis());
            ReviewNotifications.scheduleNext(this);
            showReview();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMarginEnd(dp(5));
        parent.addView(button, params);
    }

    private void showConnect() {
        selectPage("connect");
        SyncConfig config = secureStore.load();
        LinearLayout body = pageBody();
        body.addView(eyebrow("ENCRYPTED SYNC"));
        body.addView(title("连接桌面翻译器", 27));
        body.addView(subtitle("只需配对一次。以后翻译完成即自动上传，手机端自动接收。"));

        if (config == null) {
            LinearLayout form = card();
            TextView intro = label("连接你的私有同步服务", 15, INK, Typeface.BOLD);
            form.addView(intro);
            EditText server = input("https://memory.example.com", false);
            EditText registration = input("服务器注册码", true);
            form.addView(fieldLabel("服务器地址"));
            form.addView(server, fullHeight(50));
            form.addView(fieldLabel("注册码"));
            form.addView(registration, fullHeight(50));
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
                        SyncConfig created = CloudApi.register(
                                server.getText().toString(),
                                registration.getText().toString()
                        );
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
                "词条在桌面端加密，在手机端解密。服务器只保存待领取密文，确认收到后删除；无法看到单词、译文和技术语境。",
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
            row.setBackground(rounded(CARD_RAISED, 14, LINE));
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

    private void setPage(LinearLayout body) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body);
        content.removeAllViews();
        content.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        if (themeTransitioning) return;
        body.setAlpha(0f);
        body.setTranslationY(dp(12));
        AnimatorSet entrance = new AnimatorSet();
        entrance.playTogether(
                ObjectAnimator.ofFloat(body, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(body, View.TRANSLATION_Y, dp(12), 0f)
        );
        entrance.setDuration(320);
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
        card.setBackground(gradient(new int[]{
                darkMode ? Color.rgb(27, 23, 37) : Color.rgb(255, 254, 255),
                CARD
        }, 20, LINE));
        card.setElevation(0);
        return card;
    }

    private View statCard(String title, int value, String hint) {
        LinearLayout card = vertical(0);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(darkMode ? Color.rgb(25, 21, 34) : Color.rgb(255, 254, 255), 18, LINE));
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
        pill.setBackground(rounded(withAlpha(color, darkMode ? 36 : 22), 10,
                withAlpha(color, darkMode ? 86 : 54)));
        return pill;
    }

    private TextView emptyPresence(String text) {
        TextView empty = label(text, 10, MUTED, Typeface.NORMAL);
        empty.setPadding(dp(12), dp(10), dp(12), dp(10));
        empty.setBackground(rounded(CARD_RAISED, 13));
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
        button.setStateListAnimator(null);
        button.setElevation(dp(3));
        return button;
    }

    private Button linkButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTextColor(BLUE);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
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
