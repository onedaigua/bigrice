package com.bigrice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机 / 应用更新后把 dsh 提前拉起来。
 *
 * dsh 的 node 冷启动要好几秒；如果等用户点图标才开始，那就每次都是冷启动。
 * 系统广播时先起前台服务，用户真正进来时服务已经在跑，界面直接复用现有 URL
 * （见 MainActivity 的 warm entry），几秒的开屏就省掉了。
 *
 * 用户也可以不想要后台常驻：通知里的「停止」会停掉服务，这里只在开机时拉一次，不会自动复活。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        if (ctx == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        try {
            Intent start = new Intent(ctx, DshService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(start);
            else ctx.startService(start);
            DshLog.init(ctx.getFilesDir());
            DshLog.i("dshtool", "boot receiver: 提前启动 dsh (" + action + ")");
        } catch (Throwable t) {
            DshLog.e("dshtool", "boot receiver 启动失败: " + t);
        }
    }
}
