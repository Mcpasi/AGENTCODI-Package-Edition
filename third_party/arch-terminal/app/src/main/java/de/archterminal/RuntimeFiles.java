package de.archterminal;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

final class RuntimeFiles {
    static String prepare(Context context) throws Exception {
        String upgradeWarning = RootfsConfig.upgradeForLaunch(new File(context.getFilesDir(), "arch"), Native::repairDirectoryModes, AppLanguage.get(context));
        if (upgradeWarning != null) android.util.Log.w("ArchTerminal", upgradeWarning);
        File runtime = new File(context.getFilesDir(), "runtime");
        Files.createDirectories(new File(runtime, "tmp").toPath());
        StringBuilder dns = new StringBuilder();
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        LinkProperties link = manager.getLinkProperties(manager.getActiveNetwork());
        if (link != null) for (InetAddress address : link.getDnsServers()) dns.append("nameserver ").append(address.getHostAddress()).append('\n');
        if (dns.length() == 0) dns.append("nameserver 1.1.1.1\nnameserver 9.9.9.9\n");
        Files.write(new File(runtime, "resolv.conf").toPath(), dns.toString().getBytes(StandardCharsets.UTF_8));
        return upgradeWarning;
    }
    private RuntimeFiles() {}
}
