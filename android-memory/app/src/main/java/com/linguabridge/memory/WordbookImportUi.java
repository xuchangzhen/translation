package com.linguabridge.memory;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SAF and preview UI; parsing and transactions run off the main thread. */
final class WordbookImportUi {
    static final int PICK_FILE = 410;
    interface Listener { void imported(long id, String name); }
    private final Activity activity;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AlertDialog dialog;
    WordbookImportUi(Activity activity, Listener listener) { this.activity = activity; this.listener = listener; }
    void choose() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        activity.startActivityForResult(intent, PICK_FILE);
    }
    private Context theme() {
        String preference = activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", "system");
        boolean systemDark = (activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        boolean dark = "dark".equals(preference) || ("system".equals(preference) && systemDark);
        return new ContextThemeWrapper(activity, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert);
    }
    private boolean alive() { return !activity.isFinishing() && !activity.isDestroyed(); }
    void selected(Uri uri) {
        selected(uri, "auto");
    }
    private void selected(Uri uri, String encoding) {
        busy("正在读取词库…");
        worker.execute(() -> {
            try {
                String name = "词库.txt";
                try (Cursor cursor = activity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
                }
                ImportPreview preview;
                try (InputStream stream = activity.getContentResolver().openInputStream(uri)) { preview = WordbookImporter.read(stream, name, encoding); }
                PhoneticEnrichmentResult enrichment;
                try (PhoneticDictionary dictionary = new PhoneticDictionary(activity.getApplicationContext())) {
                    enrichment = new WordbookPhoneticEnricher(dictionary).enrich(preview);
                }
                PhoneticEnrichmentResult result = enrichment;
                activity.runOnUiThread(() -> { if (alive()) showPreview(result); });
            } catch (Exception e) {
                String message = e instanceof IllegalArgumentException ? e.getMessage() : "无法读取文件，请检查文件是否可用并重新选择。";
                activity.runOnUiThread(() -> {
                    if (!alive()) return;
                    if (dialog != null) dialog.dismiss();
                    dialog = new AlertDialog.Builder(theme()).setTitle("无法读取词库").setMessage(message)
                            .setPositiveButton("选择编码重试", (view, which) -> {
                                String[] encodings = {"UTF-8", "GB18030", "UTF-16LE", "UTF-16BE"};
                                dialog = new AlertDialog.Builder(theme()).setTitle("文件编码")
                                        .setItems(encodings, (choice, index) -> selected(uri, encodings[index]))
                                        .setNegativeButton("取消", null).show();
                            }).setNegativeButton("取消", null).show();
                });
            }
        });
    }
    private void busy(String message) {
        if (dialog != null) dialog.dismiss();
        dialog = new AlertDialog.Builder(theme()).setMessage(message).setCancelable(false).show();
    }
    void showPreview(ImportPreview preview) {
        showPreview(new PhoneticEnrichmentResult(preview, 0, 0, 0, false));
    }
    void showPreview(PhoneticEnrichmentResult enrichment) {
        ImportPreview preview = enrichment.preview;
        if (dialog != null) dialog.dismiss();
        LinearLayout body = new LinearLayout(theme()); body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        TextView summary = new TextView(theme());
        String dictionaryNotice = enrichment.dictionaryUnavailable ? "\n本地音标词典不可用，本次未自动补全。" : "";
        summary.setText("文件：" + preview.fileName + "（" + preview.encoding + "）\n\n检测到 " + preview.detected + " 条词汇\n有效：" + preview.items.size() + "　重复：" + preview.duplicates + "　无效：" + preview.invalid + "\n\n音标\n已有：" + enrichment.existing + "　自动补全：" + enrichment.filled + "　未找到：" + enrichment.missing + dictionaryNotice + "\n\n词库名称（同名本地词库会更新内容并保留复习进度）：");
        body.addView(summary);
        EditText name = new EditText(theme()); name.setSingleLine(true); name.setInputType(InputType.TYPE_CLASS_TEXT); name.setText(preview.name); body.addView(name);
        TextView sample = new TextView(theme());
        StringBuilder text = new StringBuilder("\n预览（最多 8 条）\n");
        for (int i = 0; i < Math.min(8, preview.items.size()); i++) {
            WordbookImporter.Item item = preview.items.get(i);
            text.append("\n").append(item.front);
            if (!item.phonetic.isEmpty()) text.append("\n").append(item.phonetic);
            text.append("\n").append(item.back).append("\n");
        }
        sample.setText(text); body.addView(sample);
        ScrollView scroll = new ScrollView(theme()); scroll.addView(body);
        dialog = new AlertDialog.Builder(theme()).setTitle("导入预览").setView(scroll).setNegativeButton("取消", null)
                .setPositiveButton("导入 " + preview.items.size() + " 条", null).create();
        dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!preview.items.isEmpty());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String title = name.getText().toString().trim();
                if (title.isEmpty() || title.length() > 100 || title.equals("桌面翻译")) { name.setError("请填写 1–100 字的本地词库名称，不能使用“桌面翻译”。"); return; }
                busy("正在导入，请稍候…");
                worker.execute(() -> {
                    try (MemoryDb db = new MemoryDb(activity.getApplicationContext())) {
                        MemoryDb.WordbookImportResult result = db.importWordbook(preview, title);
                        try { ReviewNotifications.scheduleNext(activity.getApplicationContext()); }
                        catch (RuntimeException reminderUnavailable) { /* Import has committed; reminder availability must not report it as failed. */ }
                        activity.runOnUiThread(() -> {
                            if (!alive()) return;
                            dialog.dismiss(); listener.imported(result.wordbookId, title);
                            dialog = new AlertDialog.Builder(theme()).setTitle(title + " 导入完成")
                                    .setMessage("新增 " + result.added + "\n更新 " + result.updated + "\n忽略 " + result.ignored + "\n无效 " + result.invalid + "\n自动补全音标：" + enrichment.filled + "\n未找到音标：" + enrichment.missing)
                                    .setPositiveButton("完成", null).show();
                        });
                    } catch (Exception e) { error(e, "导入失败，未提交本次更改。请检查手机剩余空间后重试。"); }
                });
            });
    }
    private void error(Exception error, String fallback) {
        String message = error instanceof IllegalArgumentException ? error.getMessage() : fallback;
        activity.runOnUiThread(() -> {
            if (!alive()) return;
            if (dialog != null) dialog.dismiss();
            dialog = new AlertDialog.Builder(theme()).setTitle("无法导入词库").setMessage(message).setPositiveButton("知道了", null).show();
        });
    }
    void destroy() { if (dialog != null) dialog.dismiss(); worker.shutdown(); }
}
