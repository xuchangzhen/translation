package com.linguabridge.memory;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class WordbookImportUiTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private WordbookImportUi ui;
    private final AtomicLong imported = new AtomicLong();
    @Before public void setup() {
        RuntimeEnvironment.getApplication().deleteDatabase("word-memory.db");
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        ui = new WordbookImportUi(activity, (id, name) -> imported.set(id));
    }
    @After public void cleanup() {
        ui.destroy(); controller.pause().stop().destroy();
        RuntimeEnvironment.getApplication().deleteDatabase("word-memory.db");
    }
    private ImportPreview preview() { return WordbookImporter.parse("word,translation\ncache,缓存", "network.csv"); }
    private int total() { try (MemoryDb db = new MemoryDb(activity)) { return db.stats(System.currentTimeMillis()).total; } }
    private EditText edit(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) { EditText result = edit(parent.getChildAt(i)); if (result != null) return result; }
        }
        return null;
    }
    @Test public void pickerUsesSafWithoutStoragePermission() {
        ui.choose();
        Intent intent = shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.getAction());
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertEquals("*/*", intent.getType());
    }
    @Test public void cancelPreviewDoesNotWriteCards() {
        ui.showPreview(preview());
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("network", edit(dialog.getWindow().getDecorView()).getText().toString());
        assertEquals(0, total());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals(0, total()); assertEquals(0, imported.get());
    }
    @Test public void reservedNameIsRejectedBeforeWriting() {
        ui.showPreview(preview());
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        EditText name = edit(dialog.getWindow().getDecorView()); name.setText("桌面翻译");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull(name.getError()); assertTrue(dialog.isShowing()); assertEquals(0, total());
    }
    @Test public void confirmationImportsAndOpensTheChosenBook() throws Exception {
        ui.showPreview(preview());
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        edit(dialog.getWindow().getDecorView()).setText("计算机网络");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        long deadline = System.currentTimeMillis() + 5000;
        while (imported.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10); shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        assertTrue("Import callback should open the selected book", imported.get() > 1);
        try (MemoryDb db = new MemoryDb(activity)) {
            assertEquals("cache", db.nextDue(System.currentTimeMillis(), imported.get()).front);
            assertEquals("计算机网络", db.wordbooks(System.currentTimeMillis()).get(1).name);
        }
    }
}
