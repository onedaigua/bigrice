import pathlib

p = pathlib.Path('app/src/com/bigrice/DshService.java')
s = p.read_text(encoding='utf-8')

# 1) notification: bigrice title, allow null/empty text, new channel label
s = s.replace('new NotificationChannel(CHANNEL, "dshtool 服务", NotificationManager.IMPORTANCE_LOW)',
              'new NotificationChannel(CHANNEL, "bigrice 服务", NotificationManager.IMPORTANCE_LOW)')
s = s.replace('ch.setDescription("DeepSeek Harness 本地 Web 服务");',
              'ch.setDescription("DeepSeek Harness 本地 Web 服务（原生 node 运行时）");')
old_title = 'b.setContentTitle("dshtool").setContentText(text)'
new_title = ('b.setContentTitle("bigrice");\n'
             '        if (text != null && !text.isEmpty()) b.setContentText(text);\n'
             '        b')
assert old_title in s
s = s.replace(old_title, new_title)

# 2) static status hook + workspace accessor, before onCreate
hook = '''
    /** 最近一次通知里的状态行；界面轮询通过 setStatus 维护它。 */
    static volatile DshService live;
    static volatile String statusText = "";
    /** 会话工作区（外部私有目录 work）；HostBridge 的 /root 就是它。 */
    static volatile File workDir;

    static File workspaceDir() {
        File dir = workDir;
        if (dir != null && dir.isDirectory()) return dir;
        DshService svc = live;
        return svc == null ? null : svc.getFilesDir();
    }

    /** 页面轮询到「正在调用工具 / 思考中…」时写入；空串表示对话已结束。 */
    public static void setStatus(String text) {
        String line = text == null ? "" : text.trim();
        DshService svc = live;
        statusText = line;
        if (svc != null) svc.updateNotification(line);
    }

    static String status() { return statusText; }

    @Override public void onCreate() {'''
assert '\n    @Override public void onCreate() {' in s
s = s.replace('\n    @Override public void onCreate() {', hook, 1)

# 3) record live in onCreate
old_init = '        DshLog.init(getFilesDir());\n        DshLog.i(TAG, "service onCreate sdk="'
new_init = ('        live = this;\n'
            '        DshLog.init(getFilesDir());\n'
            '        DshLog.i(TAG, "service onCreate sdk="')
assert old_init in s
s = s.replace(old_init, new_init, 1)

# 4) install the bigrice-ui bundle before the dsh process starts, and record the workspace
old_ws = '        workspace = resolveWorkspace();\n        DshLog.i(TAG, "workspace=" + workspace.getAbsolutePath());'
new_ws = ('        BigriceUi.ensure(this, dshDir, home, env);\n\n'
          '        workspace = resolveWorkspace();\n'
          '        workDir = workspace;\n'
          '        DshLog.i(TAG, "workspace=" + workspace.getAbsolutePath());')
assert old_ws in s
s = s.replace(old_ws, new_ws, 1)

# 5) clear live on destroy
s = s.replace('    @Override public void onDestroy() {',
              '    @Override public void onDestroy() {\n        live = null;', 1)

p.write_text(s, encoding='utf-8')
print('patched DshService.java')
