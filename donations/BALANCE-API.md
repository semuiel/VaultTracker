# API баланса пожертвований для сайта

VaultTracker и сайт используют один баланс из `donations.sqlite3`. Сайт обращается к локальному платежному сервису по HTTP; напрямую изменять SQLite из второго процесса не нужно.

## 1. Настройка ключа

В `donations/config.local.json` добавьте отдельный случайный ключ длиной не менее 40 символов, сохранив остальные параметры файла:

```json
{
  "website_api_key": "ВСТАВЬТЕ_СЮДА_СЛУЧАЙНЫЙ_СЕКРЕТ_НЕ_КОРОЧЕ_40_СИМВОЛОВ"
}
```

После изменения перезапустите `START.cmd`. Ключ нельзя помещать в Git, HTML, JavaScript браузера или мобильное приложение. Запросы должен отправлять только серверный плагин сайта.

Сервис по умолчанию слушает `127.0.0.1:18763`. Если сайт находится на другом сервере, безопаснее создать закрытый VPN или SSH-туннель до этого адреса. Не открывайте порт напрямую в интернет.

## 2. Общие параметры запросов

Все запросы отправляются методом `POST` на:

```text
http://127.0.0.1:18763
```

Заголовки:

```http
Authorization: Bearer ВАШ_WEBSITE_API_KEY
Content-Type: application/json
```

Денежные значения передаются целым числом в сотых долях монеты:

| Значение баланса | `amountMinor` |
|---:|---:|
| 1.00 | 100 |
| 10.50 | 1050 |
| 600.00 | 60000 |

Такой формат исключает ошибки округления чисел с плавающей точкой.

## 3. Получение кошелька

Запрос:

```http
POST /wallet
```

```json
{
  "ownerUuid": "e9912fd4-37ac-4bcf-aeb4-b3e24f0c6aeb"
}
```

Успешный ответ:

```json
{
  "ok": true,
  "balanceMinor": 125050,
  "blocked": false,
  "premium": true,
  "premiumUntil": 1790000000,
  "premiumPending": false
}
```

`premiumUntil` — Unix-время окончания премиума. Значение `0` означает отсутствие премиума, `-1` — бессрочный премиум.

## 4. Начисление баланса

```http
POST /donation-change
```

```json
{
  "ownerUuid": "e9912fd4-37ac-4bcf-aeb4-b3e24f0c6aeb",
  "amountMinor": 10000,
  "action": "add",
  "reason": "Пополнение с сайта, заказ SITE-1042",
  "requestId": "8bc6fb6c-e941-4fa5-8dc2-706859c0ae5a"
}
```

## 5. Списание баланса

```http
POST /donation-change
```

```json
{
  "ownerUuid": "e9912fd4-37ac-4bcf-aeb4-b3e24f0c6aeb",
  "amountMinor": 60000,
  "action": "spend",
  "reason": "Покупка на сайте, заказ SITE-1088",
  "requestId": "a2771952-a75a-4f2d-b063-3954a1816dda"
}
```

`amountMinor` всегда должен быть положительным. Направление операции задает `action`: `add` или `spend`. При недостаточном балансе списание отклоняется.

Успешный ответ обеих операций содержит актуальный баланс:

```json
{
  "ok": true,
  "balanceMinor": 65050
}
```

## 6. Защита от повторного списания

`requestId` обязателен и должен быть UUID. Создавайте один UUID на одну операцию сайта и сохраняйте его вместе с заказом.

Если сеть оборвалась после отправки запроса, повторите запрос с тем же `requestId`. Сервис вернет результат уже проведенной операции и не начислит или не спишет средства второй раз. Для новой операции нужен новый UUID.

Рекомендуемый порядок покупки на сайте:

1. Создать заказ в состоянии `pending` и сохранить его UUID как `requestId`.
2. Отправить `/donation-change` с `action: "spend"`.
3. Только после ответа `200` выдать товар и перевести заказ в `paid`.
4. При таймауте повторять тот же запрос с тем же `requestId`.
5. Не выдавать товар при ответе с ошибкой.

## 7. Коды ответа

| HTTP | Значение |
|---:|---|
| 200 | Запрос выполнен или безопасно повторен |
| 400 | Ошибка формата, UUID, суммы либо недостаточно средств |
| 401 | Ключ отсутствует или неверен |
| 403 | Операция запрещена ключу сайта |
| 404 | Неизвестный путь API |
| 503 | Временная ошибка базы или сервиса; запрос можно повторить с тем же `requestId` |

Ключ сайта имеет доступ только к `/wallet` и к операциям `add`/`spend` через `/donation-change`. Он не позволяет управлять FunPay, администраторами или премиумом. Все изменения баланса записываются в журнал с источником `website`.

## 8. Пример клиента на Java 25

Ниже используется Gson, который обычно уже есть в серверном плагине:

```java
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

public final class VaultTrackerBalanceApi {
    private static final Gson GSON = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final URI baseUri;
    private final String apiKey;

    public VaultTrackerBalanceApi(String baseUrl, String apiKey) {
        this.baseUri = URI.create(baseUrl);
        this.apiKey = apiKey;
    }

    public JsonObject wallet(UUID playerUuid) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("ownerUuid", playerUuid.toString());
        return post("/wallet", body);
    }

    public JsonObject change(UUID playerUuid, long amountMinor,
                             boolean add, String reason, UUID requestId) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("ownerUuid", playerUuid.toString());
        body.addProperty("amountMinor", amountMinor);
        body.addProperty("action", add ? "add" : "spend");
        body.addProperty("reason", reason);
        body.addProperty("requestId", requestId.toString());
        return post("/donation-change", body);
    }

    private JsonObject post(String path, JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
        HttpResponse<String> response = http.send(
                request, HttpResponse.BodyHandlers.ofString());
        JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Balance API HTTP "
                    + response.statusCode() + ": " + response.body());
        }
        return json;
    }
}
```

В Folia выполняйте HTTP-запросы асинхронно, вне потоков регионов и глобального планировщика, чтобы сайт не задерживал тики сервера.

## 9. Проверка интеграции

1. Запросить `/wallet` и запомнить `balanceMinor`.
2. Начислить тестовую сумму с новым `requestId`.
3. Повторить тот же запрос и убедиться, что баланс не изменился второй раз.
4. Списать сумму отдельным `requestId`.
5. Проверить новый баланс в Telegram и через `/wallet`.
6. Проверить отказ при недостаточном балансе и неверном ключе.
