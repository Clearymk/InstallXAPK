import config.AbiConfig;
import config.LocaleConfig;
import config.ScreenDestinyConfig;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.xmlpull.v1.XmlPullParserException;
import soot.jimple.infoflow.android.axml.AXmlNode;
import soot.jimple.infoflow.android.manifest.ProcessManifest;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InstallXapk {
    private String appPath;
    private List<File> installTask = new ArrayList<>();
    private String appId;
    private String launchActivity;
    private String emulateName;

    public InstallXapk(String appPath, String emulateName) {
        this.appPath = appPath;
        this.emulateName = emulateName;
    }

    // 检查manifest得到base apk 和 split apk
    public boolean checkPath() {
        File appFile = new File(appPath);

        if (appFile.isDirectory()) {
            int[] flag = {0, 0, 0};
            for (File splitApk : Objects.requireNonNull(appFile.listFiles())) {
                if (splitApk.getName().endsWith(".apk")) {
                    if (splitApk.getName().contains("config.")) {
                        if (LocaleConfig.isLocaleSplit(splitApk.getName().substring(0, splitApk.getName().length() - 4))) {
                            if (flag[0] == 0) {
                                this.installTask.add(splitApk);
                                flag[0] = 1;
                            }
                        } else if (AbiConfig.isAbiSplit(splitApk.getName().substring(0, splitApk.getName().length() - 4))) {
                            if (flag[1] == 0) {
                                this.installTask.add(splitApk);
                                flag[1] = 1;
                            }
                        } else if (ScreenDestinyConfig.isScreenDensitySplit(splitApk.getName().substring(0, splitApk.getName().length() - 4))) {
                            if (flag[2] == 0) {
                                this.installTask.add(splitApk);
                                flag[2] = 1;
                            }
                        }
                    } else {
                        if (checkBaseApk(splitApk.getAbsolutePath())) {
                            this.installTask.add(splitApk);
                        }
                    }
                }
            }
        }

        return true;
    }

    private boolean checkBaseApk(String apkPath) {
        try {
            ProcessManifest manifest = new ProcessManifest(apkPath);
            this.appId = manifest.getAXml().getNodesWithTag("manifest").get(0).getAttribute("package").getValue().toString();
            for (AXmlNode node : manifest.getLaunchableActivityNodes()) {
                this.launchActivity = node.getAttribute("name").getValue().toString();
                break;
            }

            if (manifest.getAXml().getNodesWithTag("manifest").get(0).getAttribute("split") == null) {
                return true;
            }
        } catch (IOException | XmlPullParserException e) {
            e.printStackTrace();
        }

        return false;
    }

    public void install() {
        try {
            // 传输文件
            for (File apkFile : this.installTask) {
                printProcess(Runtime.getRuntime().exec(String.format("adb -s %s push %s /data/local/tmp/", this.emulateName, apkFile.getAbsolutePath())));
            }


            Process p = Runtime.getRuntime().exec(String.format("adb -s %s shell pm install-create", this.emulateName));
            p.waitFor();

            BufferedReader
                    stdInput = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = null;
            String session = null;
            while ((line = stdInput.readLine()) != null) {
                String regex = "\\[(.*?)]";
                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()) {
                    session = matcher.group();
                    session = session.replace("[", "");
                    session = session.replace("]", "");
                }
                System.out.println(line);
            }

            for (int i = 0; i < installTask.size(); i++) {
                printProcess(Runtime.getRuntime().exec(String.format("adb -s %s shell pm install-write %s base%d.apk /data/local/tmp/%s", this.emulateName, session, i, installTask.get(i).getName())));
            }

            printProcess(Runtime.getRuntime().exec(String.format("adb -s %s shell pm install-commit %s", this.emulateName, session)));

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }


    private void printProcess(Process p) {
        try {
            p.waitFor();
            BufferedReader
                    stdInput = new BufferedReader(new InputStreamReader(p.getInputStream()));

            BufferedReader stdError = new BufferedReader(new
                    InputStreamReader(p.getErrorStream()));
            String line = null;

            while ((line = stdInput.readLine()) != null) {
                System.out.println(line);
            }

            while ((line = stdError.readLine()) != null) {
                System.out.println(line);
            }
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
    }

    private void deleteAndUninstall() {
        try {
            // 删除apk包
            for (File file : installTask) {
                printProcess(Runtime.getRuntime().exec(String.format("adb -s %s shell rm /data/local/tmp/", this.emulateName) + file.getName()));
            }
            // 卸载程序
            // Runtime.getRuntime().exec("adb uninstall " + this.appId);
            // this.database.updateVisitByAppId(this.appId);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String decompressXAPK(String xapkPath) {
        ZipFile xapk = new ZipFile(xapkPath);
        String outputPath = xapkPath.replaceAll(".xapk", "").replaceAll(" ", "_");

        try {
            xapk.extractAll(outputPath);
        } catch (ZipException e) {
            e.printStackTrace();
        }

        return outputPath;
    }

    public static void main(String[] args) {
        String xapkFilePath = args[0];
        File xapkFile = new File(xapkFilePath);

        if (xapkFile.getName().endsWith(".xapk")) {
            System.out.println("decompress xapk " + xapkFile.getName());

            File outputFile = new File(decompressXAPK(xapkFile.getAbsolutePath()));

            if (!outputFile.getName().equals("")) {
                InstallXapk installXapk = new InstallXapk(outputFile.getAbsolutePath(), args[1]);
                if (installXapk.checkPath()) {
                    installXapk.install();
                    installXapk.deleteAndUninstall();
                    outputFile.delete();
                }
            }
        }
    }
}
