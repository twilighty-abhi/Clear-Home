package dev.local.clearhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Remote buttons: Home -> Clear Home, TCL -> the Google TV home screen, the Netflix /
 * YouTube buttons -> those apps, and Web -> types the text sent from the phone app ("Send to
 * TV") into the selected text box, or opens it if it's a link and no text box is selected.
 *
 * The app buttons are normally handled by the Google TV launcher, but Android only lets the
 * current Home app open apps from the background. With another Home app in use (Green TV), the
 * launcher's launches are silently blocked ("Abort background activity starts"). A bound
 * accessibility service is allowed to open apps, so the buttons are handled here instead.
 *
 * Matching on Linux scan codes rather than Android key codes keeps this independent of TCL's
 * custom key numbering. Every other key is passed straight through.
 */
public class RemoteButton extends AccessibilityService {
    private static final int TCL_BUTTON_SCANCODE = 456;
    private static final int HOME_SCANCODE = 172;
    private static final android.content.ComponentName GOOGLE_TV_HOME = new android.content.ComponentName(
            "com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.home.HomeActivity");
    private static final int NETFLIX_SCANCODE = 242;
    private static final int YOUTUBE_SCANCODE = 245;
    private static final int webScancode = 651; // the remote's Web button (opened TCL's browser before)

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int scan = e.getScanCode();
        if (scan == NETFLIX_SCANCODE || scan == YOUTUBE_SCANCODE) {
            String pkg = scan == NETFLIX_SCANCODE ? "com.netflix.ninja" : "com.google.android.youtube.tv";
            Intent app = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (app == null) app = getPackageManager().getLaunchIntentForPackage(pkg);
            if (app == null) return false; // not installed: let the TV handle the button
            if (e.getAction() == KeyEvent.ACTION_UP && e.getRepeatCount() == 0) {
                Log.i("ClearHome", "Remote: app button " + scan + " -> " + pkg);
                startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        if (scan == webScancode && webScancode > 0) {
            if (e.getAction() == KeyEvent.ACTION_UP && e.getRepeatCount() == 0) sendClip();
            return true;
        }
        if (scan != HOME_SCANCODE && scan != TCL_BUTTON_SCANCODE) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            Intent target = scan == HOME_SCANCODE
                    ? new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    : new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(GOOGLE_TV_HOME);
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Log.i("ClearHome", "Remote: " + (scan == HOME_SCANCODE ? "Home -> Clear Home" : "TCL -> Google TV home"));
            try {
                startActivity(target);
            } catch (RuntimeException ex) {
                Log.w("ClearHome", "Remote: couldn't open " + target + ": " + ex);
            }
        }
        return true; // swallow both down and up so the TV's own handler doesn't also fire
    }

    /** Types the phone's text into the selected text box, or opens it when it's a link and no box is selected. */
    private void sendClip() {
        org.json.JSONObject clip = Core.get(this).house.state().optJSONObject("clip");
        String text = clip == null ? "" : clip.optString("text");
        if (text.isEmpty()) { say("Nothing to send. Use Send to TV in the flat app"); return; }
        android.view.accessibility.AccessibilityNodeInfo box = findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
        if (box != null && box.isEditable()) {
            // Paste at the cursor; if the app doesn't take a paste, set the box's text instead.
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            boolean pasted = false;
            try {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Clear Home", text));
                pasted = box.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE);
            } catch (RuntimeException ignored) {
            }
            if (!pasted) {
                CharSequence cur = box.isShowingHintText() ? "" : box.getText();
                android.os.Bundle args = new android.os.Bundle();
                args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        (cur == null ? "" : cur.toString()) + text);
                pasted = box.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            }
            Log.i("ClearHome", "Remote: Web -> " + (pasted ? "typed into text box" : "text box refused it"));
            say(pasted ? "Pasted from your phone" : "This text box doesn't accept pasting");
            return;
        }
        if (text.matches("(?i)^(https?://|www\\.)\\S+$")) {
            String url = text.toLowerCase(java.util.Locale.ROOT).startsWith("www.") ? "https://" + text : text;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Log.i("ClearHome", "Remote: Web -> opened link");
            } catch (RuntimeException ex) {
                say("No app on the TV can open this link");
            }
            return;
        }
        say("Select a text box first, then press Web");
    }

    private void say(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onServiceConnected() {
        Log.i("ClearHome", "Remote: button remap service on");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}
}
