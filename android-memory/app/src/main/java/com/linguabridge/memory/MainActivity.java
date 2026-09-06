package com.linguabridge.memory;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int BLUE = Color.rgb(23, 105, 224);
    private static final int INK = Color.rgb(23, 32, 51);
    private static final int MUTED = Color.rgb(105, 116, 139);
    private static final int SURFACE = Color.rgb(246, 248, 252);
    private static final int LINE = Color.rgb(225, 230, 239);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private MemoryDb db;
    private SecureStore secureStore;
    private FrameLayout content;
    private LinearLayout navigation;
    private TextToSpeech textToSpeech;
    private String currentPage = "home";
    private String syncMessage = "等待自动接收";

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
        db = new MemoryDb(this);
        secureStore = new SecureStore(this);
        textToSpeech = new TextToSpeech(this, this);
        ReviewNotifications.createChannels(this);
        requestNotificationPermission();
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

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE);
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
        TextView mark = label("记", 16, Color.WHITE, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(BLUE, 13));
        header.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout titles = vertical(2);
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(label("单词记忆", 20, INK, Typeface.BOLD));
        titles.addView(label("离线复习 · 加密自动接收", 11, MUTED, Typeface.NORMAL));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
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
        navigation.setBackgroundColor(Color.WHITE);
        addNav("今天", "home", this::showHome);
        addNav("词库", "library", this::showLibrary);
        addNav("连接桌面", "connect", this::showConnect);
        root.addView(navigation);
        setContentView(root);
    }

    private void addNav(String title, String page, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTag(page);
        button.setOnClickListener(view -> action.run());
        button.setBackgroundColor(Color.TRANSPARENT);
        navigation.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
    }

    private void selectPage(String page) {
        currentPage = page;
        for (int index = 0; index < navigation.getChildCount(); index++) {
            Button button = (Button) navigation.getChildAt(index);
            boolean selected = page.equals(button.getTag());
            button.setTextColor(selected ? BLUE : MUTED);
            button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
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

        Button start = primaryButton(stats.due > 0 ? "开始复习 · " + stats.due : "今天已完成");
        start.setEnabled(stats.due > 0);
        start.setOnClickListener(view -> showReview());
        body.addView(start, fullHeight(52));

        LinearLayout sync = card();
        sync.addView(label("自动接收", 12, INK, Typeface.BOLD));
        sync.addView(label(
                secureStore.load() == null ? "尚未连接桌面端" : syncMessage,
                11,
                secureStore.load() == null ? MUTED : BLUE,
                Typeface.NORMAL
        ));
        TextView detail = label(
                "端到端加密 · 服务器不可读取词条 · 手机离线时自动补收",
                10,
                MUTED,
                Typeface.NORMAL
        );
        detail.setPadding(0, dp(8), 0, 0);
        sync.addView(detail);
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
            row.addView(label(memoryCard.phonetic, 15, Color.rgb(66, 79, 105), Typeface.NORMAL));
        }
        TextView back = label(memoryCard.back, 13, MUTED, Typeface.NORMAL);
        back.setPadding(0, dp(7), 0, 0);
        row.addView(back);
        if (!memoryCard.technicalNotes.isEmpty()) {
            TextView note = label(memoryCard.technicalNotes.get(0), 11, Color.rgb(74, 91, 126), Typeface.NORMAL);
            note.setPadding(0, dp(9), 0, 0);
            row.addView(note);
        }
        return row;
    }

    private void showReview() {
        selectPage("home");
        MemoryCard memoryCard = db.nextDue(System.currentTimeMillis());
        if (memoryCard == null) {
            LinearLayout body = pageBody();
            body.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView check = label("✓", 36, Color.WHITE, Typeface.BOLD);
            check.setGravity(Gravity.CENTER);
            check.setBackground(rounded(BLUE, 30));
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
            TextView ipa = label(memoryCard.phonetic, 18, Color.rgb(64, 77, 103), Typeface.NORMAL);
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
                answer.addView(label("• " + note, 12, Color.rgb(61, 75, 103), Typeface.NORMAL));
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
            reveal.setVisibility(View.GONE);
            answer.setVisibility(View.VISIBLE);
            ratings.setVisibility(View.VISIBLE);
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
        button.setBackground(rounded(Color.rgb(242, 245, 250), 11));
        button.setOnClickListener(view -> {
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
                            status.setTextColor(Color.rgb(183, 55, 47));
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
            connected.addView(label("●  自动接收已开启", 14, Color.rgb(35, 133, 90), Typeface.BOLD));
            TextView server = label(config.serverUrl, 12, INK, Typeface.BOLD);
            server.setPadding(0, dp(14), 0, 0);
            connected.addView(server);
            connected.addView(label("设备 " + config.deviceId.substring(0, 8) + "…", 10, MUTED, Typeface.NORMAL));
            TextView state = label(syncMessage, 11, BLUE, Typeface.NORMAL);
            state.setPadding(0, dp(10), 0, 0);
            connected.addView(state);

            Button copy = primaryButton("复制桌面端配对信息");
            copy.setOnClickListener(view -> {
                copyPairing(config);
                Toast.makeText(this, "已复制；粘贴到桌面翻译器设置中", Toast.LENGTH_LONG).show();
            });
            LinearLayout.LayoutParams copyParams = fullHeight(52);
            copyParams.topMargin = dp(18);
            connected.addView(copy, copyParams);

            Button unlink = linkButton("解除本机连接");
            unlink.setTextColor(Color.rgb(183, 55, 47));
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
    }

    private LinearLayout pageBody() {
        LinearLayout body = vertical(0);
        body.setPadding(dp(20), dp(12), dp(20), dp(28));
        return body;
    }

    private LinearLayout card() {
        LinearLayout card = vertical(3);
        card.setPadding(dp(18), dp(17), dp(18), dp(17));
        card.setBackground(rounded(Color.WHITE, 18, LINE));
        return card;
    }

    private View statCard(String title, int value, String hint) {
        LinearLayout card = card();
        card.addView(label(title, 10, MUTED, Typeface.BOLD));
        TextView number = label(String.valueOf(value), 28, INK, Typeface.BOLD);
        number.setPadding(0, dp(3), 0, 0);
        card.addView(number);
        card.addView(label(hint, 9, MUTED, Typeface.NORMAL));
        return card;
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
        input.setBackground(rounded(Color.rgb(246, 248, 252), 12, LINE));
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
        button.setBackground(rounded(BLUE, 14));
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
