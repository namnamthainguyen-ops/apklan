package com.lanpower.standalone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Session callbacks never open an install prompt over a room operation. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    static volatile AppUpdater.Listener listener;
    static volatile Intent pendingUserAction;

    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        boolean pending = status == PackageInstaller.STATUS_PENDING_USER_ACTION;
        if (pending) pendingUserAction = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        else pendingUserAction = null;
        String message = pending ? "Android cần xác nhận cập nhật. Chọn Cài bản mới khi sẵn sàng."
                : status == PackageInstaller.STATUS_SUCCESS ? "Đã cập nhật Room TN20 thành công"
                : "Android chưa cài được bản mới. Chọn Cài bản mới để thử lại (mã " + status + ").";
        context.getSharedPreferences("room_tn20_updates", Context.MODE_PRIVATE).edit().putString("installStatus", message).apply();
        AppUpdater.Listener active = listener;
        if (active != null) active.changed(message, status != PackageInstaller.STATUS_SUCCESS);
    }
}
