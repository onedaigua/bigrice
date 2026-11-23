import pathlib

p = pathlib.Path('app/src/com/bigrice/MainActivity.java')
s = p.read_text(encoding='utf-8')

# --- fields --------------------------------------------------------------
old_fields = '    WebView web;\n'
new_fields = ('    WebView web;\n'
              '    HostBridge bridge;\n'
              '    static final int REQ_EXPORT_DIR = 12;\n'
              '    private boolean conversationActive;\n'
              '    private boolean statusPolling;\n')
assert old_fields in s
s = s.replace(old_fields, new_fields, 1)

# --- bridge registration -------------------------------------------------
anchor = '                if (prefs.getBoolean(DshService.KEY_RESTART, false)) {'
marker = '        root.addView(web, new FrameLayout.LayoutParams('
assert marker in s
# register the bridge right after the WebView settings block: find the client setup start
client_anchor = '        web.setWebChromeClient(new WebChromeClient() {'
assert client_anchor in s
s = s.replace(client_anchor,
              '        bridge = new HostBridge(this);\n'
              '        web.addJavascriptInterface(bridge, "BigriceHost");\n'
              '\n' + client_anchor, 1)

# --- start the status watch with the service ----------------------------
old_start = '        startDsh();\n'
assert old_start in s
s = s.replace(old_start, '        startDsh();\n        startStatusWatch();\n', 1)

# --- announce readiness when the page settles ---------------------------
old_fin = ('                    } else {\n'
           '                        hidePillNow();\n'
           '                    }\n')
assert old_fin in s
s = s.replace(old_fin,
              '                    } else {\n'
              '                        hidePillNow();\n'
              '                    }\n'
              '                    announceReady();\n', 1)

# --- stop the poll on destroy -------------------------------------------
old_destroy = '        ui.removeCallbacksAndMessages(null);\n'
assert old_destroy in s
s = s.replace(old_destroy, '        statusPolling = false;\n        ui.removeCallbacksAndMessages(null);\n', 1)

# --- new methods before the dp() helper ---------------------------------
old_dp = '    int dp(int v) {'
helpers = '''    // ---------------------------------------------------------- 状态与桥接

    /**
     * The transcript reports what it is doing through DOM attributes: a tool card
     * carries {@code data-tool}/{@code data-state}, and the streaming flag lives on
     * {@code [data-streaming]}. One line is what the shade notification shows.
     */
    static final String ACTIVITY_JS = "(function(){try{"
            + "var rows=document.querySelectorAll('[data-state]');"
            + "for(var i=rows.length-1;i>=0;i--){"
            + "var s=rows[i].getAttribute('data-state')||'';"
            + "if(s==='running'||s==='preparing'||s==='pending'||s==='streaming'){"
            + "var n=rows[i].getAttribute('data-tool')||'';"
            + "if(n)return '正在调用工具 '+n}}"
            + "if(document.querySelector('[data-streaming]'))return '思考中…';"
            + "return ''}catch(e){return ''}})()";

    /** Runs a JS snippet on the page: the bridge calls this from worker threads. */
    void evalJs(final String js) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (web == null) return;
                try {
                    web.evaluateJavascript(js, null);
                } catch (Throwable ignored) {
                    // the page can be mid-navigation; the caller retries on the next tick
                }
            }
        });
    }

    void toast(final String message) {
        ui.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    void hideKeyboard() {
        ui.post(new Runnable() {
            @Override public void run() {
                try {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null && web != null) {
                        imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
                    }
                } catch (Throwable ignored) {
                    // no IME attached
                }
            }
        });
    }

    String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    int versionCode() {
        try {
            return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Throwable e) {
            return 0;
        }
    }

    void pickExportDir() {
        ui.post(new Runnable() {
            @Override public void run() {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(intent, REQ_EXPORT_DIR);
                } catch (RuntimeException e) {
                    DshLog.w(TAG, "没有文件夹选择器: " + e);
                }
            }
        });
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXPORT_DIR || res != RESULT_OK || data == null) return;
        Uri tree = data.getData();
        if (tree == null) return;
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignored) {
            // some providers refuse persistable grants; the export still works for this session
        }
        if (bridge != null) bridge.onExportTreePicked(tree);
    }

    /** Polls the page for "tool running / thinking" and mirrors it into the notification. */
    void startStatusWatch() {
        if (statusPolling) return;
        statusPolling = true;
        ui.post(new Runnable() {
            @Override public void run() {
                if (!statusPolling) return;
                if (web != null) {
                    try {
                        web.evaluateJavascript(ACTIVITY_JS, new android.webkit.ValueCallback<String>() {
                            @Override public void onReceiveValue(String raw) {
                                String line = jsonUnquote(raw);
                                if (!line.isEmpty()) {
                                    conversationActive = true;
                                    DshService.setStatus(line);
                                } else if (conversationActive) {
                                    conversationActive = false;
                                    DshService.setStatus("");
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                        // page not ready yet
                    }
                }
                ui.postDelayed(this, 1500);
            }
        });
    }

    /** "已就绪" is news, not a permanent state: it fades unless a turn started. */
    void announceReady() {
        DshService.setStatus("已就绪");
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!conversationActive) DshService.setStatus("");
            }
        }, 4000);
    }

    /** Turns the JSON string literal returned by evaluateJavascript into text. */
    static String jsonUnquote(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isEmpty() || text.equals("null")) return "";
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            text = text.substring(1, text.length() - 1);
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n': out.append('\\n'); break;
                case 'r': out.append('\\r'); break;
                case 't': out.append('\\t'); break;
                case 'b': out.append('\\b'); break;
                case 'f': out.append('\\f'); break;
                case 'u':
                    if (i + 4 < text.length()) {
                        try {
                            out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            out.append(next);
                        }
                    } else {
                        out.append(next);
                    }
                    break;
                default: out.append(next);
            }
        }
        return out.toString();
    }

    int dp(int v) {'''
assert old_dp in s
s = s.replace(old_dp, helpers, 1)

p.write_text(s, encoding='utf-8')
print('patched MainActivity.java')
