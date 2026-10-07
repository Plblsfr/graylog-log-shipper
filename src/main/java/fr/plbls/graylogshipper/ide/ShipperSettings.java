package fr.plbls.graylogshipper.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import fr.plbls.graylogshipper.core.GelfConfig;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Application-level settings, stored in {@code graylog-log-shipper.xml}. */
@Service(Service.Level.APP)
@State(name = "GraylogLogShipper", storages = @Storage("graylog-log-shipper.xml"))
public final class ShipperSettings implements PersistentStateComponent<ShipperSettings.Data> {

    /** Serialized by IntelliJ (public fields). */
    public static final class Data {
        public boolean enabled = true;
        public String protocol = "TCP";
        public String host = "localhost";
        public int port = 12201;
        public String httpPath = "/gelf";
        public String sourceName = "";
        public String extraFields = "source_app=intellij";
        public String includeRunConfigs = "";
        public String excludeRunConfigs = "";
        public boolean parseJson = true;
        public boolean mergeMultiline = true;
        public boolean valuesAsStrings = false;
        public String separator = "_";
        /** "UTC" or "SYSTEM" (the IDE's time zone): zone assumed for log dates without zone/offset. */
        public String naiveTimeZone = "UTC";
        public int maxFieldLength = 10_000;
        public boolean includeSystemOutput = false;
        public boolean lifecycleEvents = true;
    }

    private volatile Data data = new Data();

    public static ShipperSettings getInstance() {
        return ApplicationManager.getApplication().getService(ShipperSettings.class);
    }

    @Override
    public @NotNull Data getState() {
        return data;
    }

    @Override
    public void loadState(@NotNull Data state) {
        this.data = state;
    }

    public GelfConfig toConfig() {
        return toConfig(data);
    }

    public static GelfConfig toConfig(Data d) {
        GelfConfig.Protocol protocol;
        try {
            protocol = GelfConfig.Protocol.valueOf(d.protocol.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            protocol = GelfConfig.Protocol.TCP;
        }
        String source = d.sourceName == null || d.sourceName.isBlank() ? defaultSourceName() : d.sourceName.trim();
        return new GelfConfig(protocol,
                d.host == null || d.host.isBlank() ? "localhost" : d.host.trim(),
                d.port,
                d.httpPath,
                source,
                parseExtraFields(d.extraFields),
                d.parseJson,
                d.mergeMultiline,
                d.valuesAsStrings,
                d.separator == null || d.separator.isEmpty() ? "_" : d.separator,
                Math.max(0, d.maxFieldLength),
                300,
                "SYSTEM".equalsIgnoreCase(d.naiveTimeZone) ? ZoneId.systemDefault() : ZoneOffset.UTC);
    }

    public static String defaultSourceName() {
        String h = System.getenv("COMPUTERNAME");
        if (h == null || h.isBlank()) {
            h = System.getenv("HOSTNAME");
        }
        return h == null || h.isBlank() ? "intellij" : "intellij-" + h.toLowerCase(Locale.ROOT);
    }

    /** "key=value" per line; blank lines and lines starting with # are ignored. */
    public static Map<String, String> parseExtraFields(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return out;
    }

    /** Whether the output of this run configuration must be shipped. */
    public boolean accepts(String runConfigName) {
        Data d = data;
        String name = runConfigName == null ? "" : runConfigName;
        if (!matches(d.includeRunConfigs, name, true)) {
            return false;
        }
        return !matches(d.excludeRunConfigs, name, false);
    }

    private static boolean matches(String regex, String name, boolean whenEmpty) {
        if (regex == null || regex.isBlank()) {
            return whenEmpty;
        }
        try {
            return Pattern.compile(regex.strip()).matcher(name).find();
        } catch (PatternSyntaxException e) {
            return whenEmpty;
        }
    }
}
