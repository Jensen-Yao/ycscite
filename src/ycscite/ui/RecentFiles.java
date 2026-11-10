package ycscite.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** 最近打开记录（~/.ycscite/recent.properties，最多 6 条）。 */
public final class RecentFiles {

    private static Path store() {
        return Path.of(System.getProperty("user.home"), ".ycscite", "recent.properties");
    }

    public static synchronized void add(String path) {
        Properties props = new Properties();
        try (var in = Files.newInputStream(store())) {
            props.load(in);
        } catch (IOException ignored) { }
        List<String> items = list();
        items.remove(path);
        items.add(0, path);
        while (items.size() > 6) items.remove(items.size() - 1);
        props.clear();
        for (int i = 0; i < items.size(); i++) props.setProperty("recent." + i, items.get(i));
        try {
            Files.createDirectories(store().getParent());
            try (var out = Files.newOutputStream(store())) {
                props.store(out, "Ycscite recent files");
            }
        } catch (IOException ignored) { }
    }

    public static synchronized List<String> list() {
        Properties props = new Properties();
        try (var in = Files.newInputStream(store())) {
            props.load(in);
        } catch (IOException ignored) { return new ArrayList<>(); }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String v = props.getProperty("recent." + i);
            if (v != null) out.add(v);
        }
        return out;
    }
}
