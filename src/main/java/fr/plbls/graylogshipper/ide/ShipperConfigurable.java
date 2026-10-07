package fr.plbls.graylogshipper.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import fr.plbls.graylogshipper.core.GelfConfig;
import fr.plbls.graylogshipper.core.GelfDispatcher;
import fr.plbls.graylogshipper.core.GelfMessageFactory;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.FlowLayout;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Settings ▸ Tools ▸ Graylog Log Shipper. */
public final class ShipperConfigurable implements Configurable {

    private static final String TZ_UTC = "UTC";
    private static final String TZ_IDE = "Fuseau de l'IDE (" + java.time.ZoneId.systemDefault() + ")";

    private JPanel panel;
    private JBCheckBox enabled;
    private ComboBox<GelfConfig.Protocol> protocol;
    private JBTextField host;
    private JBTextField port;
    private JBTextField httpPath;
    private JBTextField sourceName;
    private JBTextArea extraFields;
    private JBTextField includeRunConfigs;
    private JBTextField excludeRunConfigs;
    private JBCheckBox parseJson;
    private JBCheckBox mergeMultiline;
    private JBCheckBox valuesAsStrings;
    private ComboBox<String> separator;
    private ComboBox<String> naiveTimeZone;
    private JBTextField maxFieldLength;
    private JBCheckBox includeSystemOutput;
    private JBCheckBox lifecycleEvents;
    private JBLabel testResult;
    private JBLabel stats;

    @Override
    public @Nls String getDisplayName() {
        return "Graylog Log Shipper";
    }

    @Override
    public @Nullable JComponent createComponent() {
        enabled = new JBCheckBox("Envoyer la sortie des configurations Run/Debug vers Graylog");
        protocol = new ComboBox<>(GelfConfig.Protocol.values());
        host = new JBTextField();
        port = new JBTextField(6);
        httpPath = new JBTextField();
        sourceName = new JBTextField();
        sourceName.getEmptyText().setText(ShipperSettings.defaultSourceName());
        extraFields = new JBTextArea(4, 40);
        extraFields.setFont(UIUtil.getLabelFont());
        includeRunConfigs = new JBTextField();
        includeRunConfigs.getEmptyText().setText("toutes (regex, ex. : api|batch)");
        excludeRunConfigs = new JBTextField();
        excludeRunConfigs.getEmptyText().setText("aucune (regex, ex. : Test|Tests in)");
        parseJson = new JBCheckBox("Lignes JSON : transformer les propriétés en champs Graylog (sous-objets aplatis)");
        mergeMultiline = new JBCheckBox("Logs texte : regrouper les stack traces multilignes en un seul message");
        valuesAsStrings = new JBCheckBox("Envoyer toutes les valeurs en texte (évite les conflits de type dans l'index)");
        separator = new ComboBox<>(new String[]{"_", "."});
        naiveTimeZone = new ComboBox<>(new String[]{TZ_UTC, TZ_IDE});
        maxFieldLength = new JBTextField(6);
        includeSystemOutput = new JBCheckBox("Inclure les lignes système d'IntelliJ (ligne de commande, « Process finished… »)");
        lifecycleEvents = new JBCheckBox("Envoyer un événement au démarrage et à l'arrêt de chaque process");

        JButton test = new JButton("Tester la connexion");
        testResult = new JBLabel();
        test.addActionListener(e -> testConnection());
        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        testRow.add(test);
        testRow.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(10)));
        testRow.add(testResult);

        stats = new JBLabel();
        stats.setForeground(UIUtil.getContextHelpForeground());

        JBLabel extraHelp = new JBLabel("Un champ par ligne, clé=valeur. Pratique pour router vers un flux (stream rule).");
        extraHelp.setForeground(UIUtil.getContextHelpForeground());

        panel = FormBuilder.createFormBuilder()
                .addComponent(enabled)
                .addSeparator()
                .addLabeledComponent("Protocole GELF :", protocol)
                .addLabeledComponent("Hôte Graylog :", host)
                .addLabeledComponent("Port :", port)
                .addLabeledComponent("Chemin HTTP :", httpPath)
                .addComponent(testRow)
                .addSeparator()
                .addLabeledComponent("Source (champ host) :", sourceName)
                .addLabeledComponent("Champs ajoutés :", new JBScrollPane(extraFields))
                .addComponentToRightColumn(extraHelp)
                .addLabeledComponent("Configurations incluses :", includeRunConfigs)
                .addLabeledComponent("Configurations exclues :", excludeRunConfigs)
                .addSeparator()
                .addComponent(parseJson)
                .addLabeledComponent("Séparateur des sous-objets :", separator)
                .addLabeledComponent("Dates sans fuseau (ni Z, ni +02:00) :", naiveTimeZone)
                .addLabeledComponent("Longueur max d'un champ (0 = illimitée) :", maxFieldLength)
                .addComponent(valuesAsStrings)
                .addComponent(mergeMultiline)
                .addComponent(includeSystemOutput)
                .addComponent(lifecycleEvents)
                .addSeparator()
                .addComponent(stats)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        protocol.addActionListener(e -> httpPath.setEnabled(protocol.getItem() == GelfConfig.Protocol.HTTP));
        reset();
        return panel;
    }

    private ShipperSettings.Data fromUi() {
        ShipperSettings.Data d = new ShipperSettings.Data();
        d.enabled = enabled.isSelected();
        d.protocol = protocol.getItem().name();
        d.host = host.getText().trim();
        d.port = parseInt(port.getText(), -1);
        d.httpPath = httpPath.getText().trim();
        d.sourceName = sourceName.getText().trim();
        d.extraFields = extraFields.getText();
        d.includeRunConfigs = includeRunConfigs.getText().trim();
        d.excludeRunConfigs = excludeRunConfigs.getText().trim();
        d.parseJson = parseJson.isSelected();
        d.mergeMultiline = mergeMultiline.isSelected();
        d.valuesAsStrings = valuesAsStrings.isSelected();
        d.separator = (String) separator.getSelectedItem();
        d.naiveTimeZone = TZ_IDE.equals(naiveTimeZone.getSelectedItem()) ? "SYSTEM" : "UTC";
        d.maxFieldLength = parseInt(maxFieldLength.getText(), -1);
        d.includeSystemOutput = includeSystemOutput.isSelected();
        d.lifecycleEvents = lifecycleEvents.isSelected();
        return d;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean same(ShipperSettings.Data a, ShipperSettings.Data b) {
        return a.enabled == b.enabled
                && Objects.equals(a.protocol, b.protocol)
                && Objects.equals(a.host, b.host)
                && a.port == b.port
                && Objects.equals(a.httpPath, b.httpPath)
                && Objects.equals(a.sourceName, b.sourceName)
                && Objects.equals(a.extraFields, b.extraFields)
                && Objects.equals(a.includeRunConfigs, b.includeRunConfigs)
                && Objects.equals(a.excludeRunConfigs, b.excludeRunConfigs)
                && a.parseJson == b.parseJson
                && a.mergeMultiline == b.mergeMultiline
                && a.valuesAsStrings == b.valuesAsStrings
                && Objects.equals(a.separator, b.separator)
                && Objects.equals(a.naiveTimeZone, b.naiveTimeZone)
                && a.maxFieldLength == b.maxFieldLength
                && a.includeSystemOutput == b.includeSystemOutput
                && a.lifecycleEvents == b.lifecycleEvents;
    }

    @Override
    public boolean isModified() {
        return !same(fromUi(), ShipperSettings.getInstance().getState());
    }

    @Override
    public void apply() throws ConfigurationException {
        ShipperSettings.Data d = fromUi();
        validate(d);
        ShipperSettings.Data target = ShipperSettings.getInstance().getState();
        target.enabled = d.enabled;
        target.protocol = d.protocol;
        target.host = d.host;
        target.port = d.port;
        target.httpPath = d.httpPath;
        target.sourceName = d.sourceName;
        target.extraFields = d.extraFields;
        target.includeRunConfigs = d.includeRunConfigs;
        target.excludeRunConfigs = d.excludeRunConfigs;
        target.parseJson = d.parseJson;
        target.mergeMultiline = d.mergeMultiline;
        target.valuesAsStrings = d.valuesAsStrings;
        target.separator = d.separator;
        target.naiveTimeZone = d.naiveTimeZone;
        target.maxFieldLength = d.maxFieldLength;
        target.includeSystemOutput = d.includeSystemOutput;
        target.lifecycleEvents = d.lifecycleEvents;

        ShipperService service = ApplicationManager.getApplication().getServiceIfCreated(ShipperService.class);
        if (service != null) {
            service.reconfigure();
        }
    }

    private static void validate(ShipperSettings.Data d) throws ConfigurationException {
        if (d.host.isEmpty()) {
            throw new ConfigurationException("Indique l'hôte Graylog (ex. : localhost).");
        }
        if (d.port < 1 || d.port > 65535) {
            throw new ConfigurationException("Le port doit être un nombre entre 1 et 65535.");
        }
        if (d.maxFieldLength < 0) {
            throw new ConfigurationException("La longueur max d'un champ doit être un nombre ≥ 0.");
        }
        checkRegex(d.includeRunConfigs, "Configurations incluses");
        checkRegex(d.excludeRunConfigs, "Configurations exclues");
    }

    private static void checkRegex(String regex, String label) throws ConfigurationException {
        if (regex.isEmpty()) {
            return;
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new ConfigurationException(label + " : expression régulière invalide (" + e.getDescription() + ").");
        }
    }

    @Override
    public void reset() {
        ShipperSettings.Data d = ShipperSettings.getInstance().getState();
        enabled.setSelected(d.enabled);
        GelfConfig.Protocol p;
        try {
            p = GelfConfig.Protocol.valueOf(d.protocol);
        } catch (RuntimeException e) {
            p = GelfConfig.Protocol.TCP;
        }
        protocol.setItem(p);
        host.setText(d.host);
        port.setText(String.valueOf(d.port));
        httpPath.setText(d.httpPath);
        httpPath.setEnabled(p == GelfConfig.Protocol.HTTP);
        sourceName.setText(d.sourceName);
        extraFields.setText(d.extraFields);
        includeRunConfigs.setText(d.includeRunConfigs);
        excludeRunConfigs.setText(d.excludeRunConfigs);
        parseJson.setSelected(d.parseJson);
        mergeMultiline.setSelected(d.mergeMultiline);
        valuesAsStrings.setSelected(d.valuesAsStrings);
        separator.setSelectedItem(d.separator);
        naiveTimeZone.setSelectedItem("SYSTEM".equalsIgnoreCase(d.naiveTimeZone) ? TZ_IDE : TZ_UTC);
        maxFieldLength.setText(String.valueOf(d.maxFieldLength));
        includeSystemOutput.setSelected(d.includeSystemOutput);
        lifecycleEvents.setSelected(d.lifecycleEvents);
        testResult.setText("");
        refreshStats();
    }

    private void refreshStats() {
        ShipperService service = ApplicationManager.getApplication().getServiceIfCreated(ShipperService.class);
        if (service == null) {
            stats.setText("Aucun log envoyé depuis le démarrage de l'IDE.");
            return;
        }
        GelfDispatcher.Stats s = service.stats();
        String text = "Depuis le démarrage de l'IDE : " + s.sent() + " envoyés, " + s.failed() + " en échec, "
                + s.dropped() + " perdus (file pleine), " + s.queued() + " en attente.";
        if (s.lastError() != null) {
            text += " Dernière erreur : " + s.lastError();
        }
        stats.setText(text);
    }

    private void testConnection() {
        ShipperSettings.Data d = fromUi();
        try {
            validate(d);
        } catch (ConfigurationException e) {
            testResult.setText("✗ " + e.getMessage());
            return;
        }
        GelfConfig cfg = ShipperSettings.toConfig(d);
        testResult.setText("Envoi…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Map<String, Object> msg = GelfMessageFactory.event("Test de connexion depuis IntelliJ", 6, cfg,
                    Map.of(), Map.of("_ij_event", "connection_test"));
            String error = GelfDispatcher.sendOnce(cfg, msg);
            String text;
            if (error != null) {
                text = "✗ " + error;
            } else if (cfg.protocol() == GelfConfig.Protocol.UDP) {
                text = "✓ Envoyé (UDP : pas d'accusé de réception, vérifie dans Graylog)";
            } else if (cfg.protocol() == GelfConfig.Protocol.TCP) {
                text = "✓ Connexion TCP OK, message de test envoyé";
            } else {
                text = "✓ Message de test accepté par Graylog (HTTP 202)";
            }
            SwingUtilities.invokeLater(() -> {
                testResult.setText(text);
                refreshStats();
            });
        });
    }

    @Override
    public void disposeUIResources() {
        panel = null;
    }
}
