# Graylog Log Shipper — plugin IntelliJ

Envoie en direct la sortie console de tes configurations **Run / Debug** vers une input **GELF** de Graylog
(TCP, UDP ou HTTP), pour la retrouver dans un flux (stream) dédié.

- **Logs JSON** (Logback/Logstash encoder, ECS, pino…) : chaque propriété devient un champ Graylog.
  Les sous-objets sont aplatis et indexés (`{"http":{"status":200}}` → `http_status = 200`).
  Les **chaînes qui contiennent du JSON restent de simples chaînes** : jamais réinterprétées.
- **Logs texte** : niveau détecté (`ERROR`, `WARN`, `INFO`…), stack traces regroupées en un seul message
  (`short_message` = 1re ligne, `full_message` = trace complète).
- Chaque message porte le projet, la configuration lancée, Run/Debug et un id de session, plus tes propres champs fixes.
- Si Graylog est arrêté, l'IDE n'est jamais ralenti : les logs sont ignorés, une notification s'affiche
  (au plus une par minute) et la connexion est retentée toutes les 2 s.

## Construire et installer

Prérequis : JDK 21 (celui d'IntelliJ convient), accès Internet au premier build (Gradle 9.1 et le SDK IntelliJ sont téléchargés).

```bash
./gradlew buildPlugin        # → build/distributions/graylog-log-shipper-1.0.0.zip
./gradlew test               # tests du cœur (parsing JSON, regroupement, transports)
./gradlew runIde             # essayer dans un IntelliJ « bac à sable »
```

Installation : **Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk…** puis choisir le `.zip`.
Compatible IntelliJ 2025.2 et suivants (et les autres IDE JetBrains : WebStorm, PyCharm…).

## Côté Graylog

1. **System ▸ Inputs** : lancer une input **GELF TCP** (ou UDP / HTTP), bind `0.0.0.0`, port `12201`.
2. Dans le `docker-compose.yml` de Graylog, publier le port :
   ```yaml
   ports:
     - "12201:12201"       # GELF TCP (et HTTP si tu choisis l'input GELF HTTP)
     - "12201:12201/udp"   # GELF UDP
   ```
3. **Streams ▸ Create stream** « IntelliJ », puis une règle :
   champ `source_app`, *match exactly*, valeur `intellij`
   (cocher « Remove matches from Default Stream » si tu veux qu'ils n'apparaissent que là).
4. Dans IntelliJ : **Settings ▸ Tools ▸ Graylog Log Shipper ▸ Tester la connexion**.

Tu peux faire d'autres flux avec tes propres champs fixes (`env=local`, `team=b2b`…) ou sur `ij_run_config`.

## Champs envoyés

| Champ Graylog | Contenu |
|---|---|
| `message` | `message`/`msg` du JSON, sinon la ligne |
| `full_message` | `stack_trace`/`stacktrace`/`exception` du JSON, ou la stack trace texte regroupée |
| `timestamp` | `@timestamp`/`timestamp`/`time`/`ts` du JSON (ISO 8601 ou epoch), sinon l'heure de réception |
| `level` / `level_name` | niveau syslog (3 = erreur, 4 = warn, 6 = info, 7 = debug) et le niveau d'origine |
| `source` | réglage « Source », par défaut `intellij-<machine>` |
| `ij_project`, `ij_run_config`, `ij_run_type`, `ij_executor`, `ij_session` | contexte du lancement |
| `ij_stream` | `stdout` / `stderr` / `system` |
| `ij_format` | `json`, `text` ou `event` (démarrage/arrêt du process) |
| tes champs fixes | ex. `source_app=intellij` |
| toutes les autres propriétés JSON | aplaties, `-`/espaces/`@` remplacés par `_` |

Exemple : la ligne

```json
{"@timestamp":"2026-10-06T15:53:12.123+02:00","level":"WARN","message":"GET /orders","flowId":"7f1c",
 "http":{"status":200,"request":{"method":"GET"}},"payload":"{\"orderId\":42}","tags":["a","b"]}
```

donne `message=GET /orders`, `level=4`, `level_name=WARN`, `flowId=7f1c`, `http_status=200`,
`http_request_method=GET`, `payload={"orderId":42}` (chaîne), `tags=["a","b"]` (chaîne).

Noms réservés par Graylog (`id`, `source`, `host`, `version`, `message`…) : préfixés par `json_`
(ex. `@version` → `json_version`).

## Réglages (Settings ▸ Tools ▸ Graylog Log Shipper)

- **Activé** (aussi dans le menu *Tools ▸ Envoyer les logs Run/Debug vers Graylog*, effet immédiat même sur un process en cours).
- **Protocole / hôte / port / chemin HTTP** — TCP conseillé en local (fiable et ordonné).
- **Configurations incluses / exclues** — regex sur le nom de la Run config (ex. exclure `Test|Tests in`).
- **Dates sans fuseau** — pour un `@timestamp` sans `Z` ni décalage (`2026-10-06T13:53:12`) : lu en **UTC** (par défaut) ou dans le fuseau de l'IDE. Une date avec `Z` ou `+02:00` est toujours respectée telle quelle.
- **Séparateur des sous-objets** — `_` (par défaut) ou `.`.
- **Longueur max d'un champ** — 10 000 caractères par défaut : OpenSearch refuse un champ texte « keyword » de plus de 32 766 octets.
- **Tout envoyer en texte** — utile si un même champ est tantôt nombre, tantôt texte (conflit de type → message rejeté à l'indexation).
- **Regrouper les stack traces**, **lignes système d'IntelliJ**, **événements démarrage/arrêt**.

## Limites

- Ne voit que ce qui passe par la console d'un Run/Debug (pas les logs affichés dans l'onglet *Services ▸ Docker*).
- Les tableaux JSON sont envoyés comme du texte, pour ne pas multiplier les champs.
- Logs JSON multilignes (pretty-print) : traités comme du texte.

## Structure

```
core/   Java pur, sans dépendance IntelliJ (testé) :
        Json, GelfMessageFactory (ligne → GELF), LogSession (lignes, multiligne),
        GelfTransport (TCP/UDP chunké/HTTP), GelfDispatcher (file + thread d'envoi)
ide/    RunLogExecutionListener (s'accroche aux process), ShipperService, ShipperSettings,
        ShipperConfigurable (page de réglages), ToggleShippingAction
```
