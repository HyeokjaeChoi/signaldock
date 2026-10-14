# SDK 통합 가이드

[English](integration-guide.md)

이 가이드는 앱 개발자가 SignalDock Android SDK를 앱에 추가하고 사용하는 방법을 설명합니다.
새로운 동작은 추가하지 않습니다. 모든 문장은 출처를 밝힙니다. 출처는 [spec](../.scratch/signaldock-portfolio/spec.md)("spec 3"은 3절), [architecture](../architecture.md), [ADR](adr/)입니다.
SDK의 모든 public 선언에는 KDoc이 있습니다. KDoc은 이 가이드와 일치합니다.

SDK 패키지는 `local.signaldock`, 릴리스 버전은 `0.1.0`, 좌표는 `local.signaldock:sdk-android:0.1.0`입니다(spec 8.C, Q126).

## 목차

1. [설치](#1-설치)
2. [초기화](#2-초기화)
3. [이벤트 수집](#3-이벤트-수집)
4. [TrackResult의 모든 경우 처리](#4-trackresult의-모든-경우-처리)
5. [취소](#5-취소)
6. [오프라인, 재시도, 큐 한도](#6-오프라인-재시도-큐-한도)
7. [Diagnostics](#7-diagnostics)
8. [개인정보](#8-개인정보)
9. [버전과 계약 버전](#9-버전과-계약-버전)
10. [문제 해결](#10-문제-해결)
11. [SDK가 하지 않는 일](#11-sdk가-하지-않는-일)
12. [코드 샘플](#12-코드-샘플)

## 1. 설치

SDK는 ZIP으로 묶은 로컬 Maven repository 폴더입니다. Maven Central에는 없습니다(spec 1, spec 6 "Reproduction and deliverables", [ADR-0009](adr/0009-maven-zip-and-consumer-app.md) 결정 1).

1. 저장소 루트에서 ZIP을 만듭니다(ADR-0009 결정 2).

   ```sh
   sdk/gradlew -p sdk :sdk-android:signaldockMavenZip
   ```

   결과 파일은 `sdk/sdk-android/build/distributions/signaldock-sdk-android-0.1.0-maven.zip`입니다.
2. 폴더에 압축을 풉니다.
3. 그 폴더를 `local.signaldock` 그룹에만 쓰는 repository로 추가하고, 좌표에 의존합니다(ADR-0009 결정 3).
   독립 consumer 앱이 실제 예시입니다. [`settings.gradle.kts`](../compatibility/consumer-android/settings.gradle.kts)와 [`app/build.gradle.kts`](../compatibility/consumer-android/app/build.gradle.kts)를 보세요.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("/path/to/unzipped/folder")
            content { includeGroup("local.signaldock") }
        }
    }
}
// app/build.gradle.kts
dependencies { implementation("local.signaldock:sdk-android:0.1.0") }
```

0.1.0 AAR의 요구 사항:

- `compileSdk` 36 이상, `minSdk` 24 이상([ADR-0006](adr/0006-sdk-delivery-decisions.md) 결정 1, spec 2).
- Kotlin 2.2 이상과 AGP 9.x. SDK 클래스 metadata가 2.2이므로 AGP 9의 기본 built-in Kotlin으로 동작합니다(ADR-0009 결정 4, Q132). AGP 8.x 앱은 0.1.0을 쓸 수 없습니다(ADR-0009 결정 4).
- 앱이 `INTERNET` 권한을 선언해야 합니다(consumer 앱의 [manifest](../compatibility/consumer-android/app/src/main/AndroidManifest.xml)에 있습니다. [README](../README.md) "Add the SDK to an app"). SDK 자체의 R8 규칙은 필요 없습니다(ADR-0009 결정 6).
- `https://` endpoint를 쓰세요. SDK는 `http://`를 `https://`로 바꾸지 않습니다. `http://` 로컬 서버를 쓰려면 앱이 그 host에 cleartext를 허용해야 합니다. consumer 앱은 `10.0.2.2`, `127.0.0.1`, `localhost`에만 허용합니다(ADR-0009 결정 5, Q134).

## 2. 초기화

`Application`에서 `SignalDock.initialize()`를 한 번 호출합니다(spec 3 "Lifetime and cancellation").
suspend 함수입니다. 저장소 준비는 메인 스레드 밖에서 합니다(architecture "SDK lifecycle and cancellation").
SDK는 application context만 보관합니다(spec 3).

```kotlin
public suspend fun initialize(context: Context, config: SignalDockConfig): InitResult
```

`SignalDockConfig`에는 `endpoint`와 `collectionKey`가 있습니다. 선택 항목으로 `logLevel`(기본값 `WARN`)과 timeout 네 개가 있습니다. 기본값은 call 30초, connect/read/write 각 10초입니다. timeout은 양수이고 유한해야 합니다(architecture "Agreed defaults, pending measurement", spec 8 Q117). 설정은 SDK 수명 동안 고정입니다(spec 3). `toString()`은 collection key를 숨깁니다(spec 8 Q115).

```kotlin
// sample: initialize
class ShopApplication : Application() {
    /** The one initialization attempt. Activities await it before they call track(). */
    lateinit var sdkInit: Deferred<InitResult>
        private set

    override fun onCreate() {
        super.onCreate()
        sdkInit = CoroutineScope(SupervisorJob() + Dispatchers.Default).async {
            SignalDock.initialize(
                this@ShopApplication,
                SignalDockConfig(endpoint = "https://events.example.com", collectionKey = "your-collection-key"),
            )
        }
    }
}

fun describe(result: InitResult): String = when (result) {
    is InitResult.Success -> "ready"
    is InitResult.Failure -> when (val e = result.error) {
        is InitError.InvalidConfiguration -> "fix the configuration: ${e.message}"
        InitError.ConfigurationConflict -> "another configuration is active"
        is InitError.StorageFailure -> "storage failed (${e.message}); call initialize again later"
    }
}
// end sample
```

규칙:

- `track()`은 `InitResult.Success`로 받은 `SignalDock` 인스턴스에서만 호출합니다(architecture "SDK lifecycle and cancellation", spec 3).
- 같은 설정의 호출은 시도 하나를 공유합니다. 성공은 재사용합니다. 다른 설정은 `InitError.ConfigurationConflict`를 반환합니다(spec 3, architecture "SDK lifecycle and cancellation").
- 실패한 시도는 자동으로 반복하지 않습니다. 다시 하려면 `initialize()`를 다시 호출합니다. 큐와 installation ID는 유지됩니다(architecture "SDK lifecycle and cancellation", spec 8 Q112).
- 호출자가 취소되면 자기 대기만 멈춥니다. 시도는 SDK 프로세스 scope에서 계속됩니다. 프로세스가 죽으면 완료를 보장하지 않습니다(architecture "SDK lifecycle and cancellation", spec 8 Q114).
- 프로세스가 다시 만들어지면 SDK는 앱의 `Application` 코드를 통해 다시 시작하고 큐를 복구합니다(spec 3).
- SDK는 프로세스당 인스턴스 하나입니다. Compose와 ViewModel에 의존하지 않습니다(spec 2, architecture "SDK lifecycle and cancellation").

## 3. 이벤트 수집

`track()`은 suspend 함수입니다. 이벤트를 기기에 저장합니다(spec 3 "Collection").

```kotlin
public suspend fun track(name: String, block: EventBuilder.() -> Unit = {}): TrackResult
```

```kotlin
// sample: track
suspend fun onProductViewed(sdk: SignalDock): String {
    val result = sdk.track("product_viewed") {
        property("product_id", "P-001")   // string
        property("quantity", 2)           // number
        property("price", 12.5)           // number
        property("is_test", true)         // boolean
    }
    return when (result) {
        is TrackResult.Success -> "stored on the device: ${result.eventId}" // not server receipt
        is TrackResult.Invalid -> "fix the call: ${result.reason} (${result.field})" // nothing stored
        TrackResult.QueueFull -> "queue is full; the event was dropped" // existing events are kept
        is TrackResult.StorageError -> "storage failed: ${result.message}" // nothing stored
    }
}
// end sample
```

사용자 동작 하나에 `track()`을 한 번 호출합니다. recomposition이나 취소 뒤에 자동으로 다시 수집하지 마세요(spec 6 "Demo and diagnostics", architecture "Components and responsibilities").
이벤트 이름과 property key는 앱이 정합니다. SDK에는 쇼핑 전용 모델이 없습니다(spec 3).

### 이름과 property 규칙

SDK는 입력을 trim, 정규화, 변환하지 않습니다. 대신 `TrackResult.Invalid`를 반환합니다(spec 3 "Collection", spec 8 Q64, Q84, Q85).

| 규칙 | `InvalidReason` | 출처 |
| --- | --- | --- |
| 이름과 property key는 비어 있지 않고 공백만으로 이루어지지 않음 | `EMPTY_NAME` | spec 8 Q64 |
| 이름과 property key의 앞뒤에 공백이 없음. 안쪽 공백과 한글은 허용 | `LEADING_OR_TRAILING_WHITESPACE` | spec 8 Q64 |
| 텍스트(이름, key, 문자열 값)에 NUL 문자와 짝이 없는 surrogate가 없음 | `FORBIDDEN_CHARACTER` | spec 8 Q85 |
| property key는 한 번만 사용. 같은 값이라도 두 번째 사용은 호출 전체를 실패시킴 | `DUPLICATE_KEY` | spec 8 Q63 |
| 소수는 유한한 수. NaN과 무한대는 불가 | `NON_FINITE_NUMBER` | spec 3 |
| 정수는 ±(2^53 - 1) 이내. `Double`로 넘긴 정수형 값에도 적용 | `NUMBER_OUT_OF_RANGE` | spec 3, architecture "Contracts, generated sources, and JSON" |
| 이벤트 하나는 공통 필드를 포함해 전송 JSON 16 KiB 이하 | `EVENT_TOO_LARGE` | spec 4 |

그 밖의 규칙:

- property는 평평한 구조입니다. 값은 문자열, 숫자, 불리언입니다. 중첩 객체, 배열, 명시적 null은 없습니다. 값이 없으면 key를 생략합니다(spec 3).
- `property` overload는 `String`, `Boolean`, `Int`, `Long`, `Double`입니다. `Float` overload는 없습니다. `Float`를 넓히면 값이 바뀌기 때문입니다([ADR-0003](adr/0003-sdk-storage-decisions.md) 결정 5).
- 큰 ID는 문자열로 보내세요. 정확한 금액은 허용 범위 안에서 최소 통화 단위의 정수로 보내세요(spec 3).
- 공백 집합은 서버와 같은 고정 목록입니다. `Char.isWhitespace()`가 기준이 아닙니다(ADR-0003 결정 4).
- 텍스트는 Unicode 정규화 없이 정확히 비교합니다. 모양이 같은 두 이름이 서로 다른 이름일 수 있습니다(spec 8 Q84).
- SDK가 `eventId`(UUIDv4), `installationId`, `occurredAt`(`track()`이 호출을 처리한 기기 시각), `sdkVersion`을 추가합니다. 앱은 `eventId`와 `occurredAt`을 정할 수 없습니다(spec 3 "Common information").

## 4. TrackResult의 모든 경우 처리

| 결과 | 의미 | 앱이 할 일 | 출처 |
| --- | --- | --- | --- |
| `Success(eventId)` | 이벤트가 기기에 저장됨. 서버 수신이 아님 | 없음. SDK가 나중에 보냄 | spec 3, architecture "Commit boundaries" |
| `Invalid(reason, field)` | 입력이 규칙을 어김. 저장된 것 없음. `field`는 `"name"` 또는 property key. `EVENT_TOO_LARGE`이면 `null` | 코드의 호출을 고침. 같은 입력을 다시 보내지 않음 | spec 3, spec 8 Q63 |
| `QueueFull` | 큐가 10,000개 또는 16 MiB에 도달. SDK는 기존 이벤트를 유지하고 이 이벤트는 저장하지 않음 | 앱이 이벤트를 버릴지 나중에 다시 시도할지 정함. SDK는 대신 저장하지 않음 | spec 4 |
| `StorageError(message)` | 저장 실패. `message`는 예외의 simple class name. 이벤트 내용 없음 | 이벤트가 저장되지 않은 것으로 처리. 사용자에게 자동으로 보이지 않음 | spec 3, [ADR-0017](adr/0017-sdk-crash-safety.md) 결정 1 |

참고:

- `track()`은 이 경우들에서 예외를 던지지 않습니다. 앱이 직접 쓴 `track { ... }` 블록의 예외는 잡지 않습니다(ADR-0017 결정 6). `NoClassDefFoundError` 같은 `Error`도 잡지 않습니다(ADR-0017 결정 3).
- 호출자 취소는 `TrackResult`가 아닙니다. 다음 절을 보세요.
- `Success`는 서버가 이벤트를 가졌다는 뜻이 아닙니다. 기기 상태는 `diagnostics()`로 봅니다(spec 6 "Demo and diagnostics"). 서버 수신은 SDK가 아니라 Dashboard에 나타납니다(architecture "Components and responsibilities").
- SDK는 toast나 dialog를 띄우지 않습니다(spec 3).

## 5. 취소

`track()`은 `CancellationException`을 전파합니다. 이를 `TrackResult`로 바꾸지 않습니다(spec 3 "Lifetime and cancellation", architecture "SDK lifecycle and cancellation").

예외가 앱에 도달했을 때 이벤트가 이미 저장되어 있을 수 있습니다. SDK는 저장된 이벤트를 유지하고 나중에 보냅니다(spec 3).

**`CancellationException` 뒤에 같은 동작을 다시 수집하지 마세요.** `track()`을 다시 호출하면 새 `eventId`로 두 번째 이벤트가 만들어집니다. 그러면 서버에 동작 하나당 이벤트 두 개가 생깁니다(spec 3, architecture "SDK lifecycle and cancellation").

```kotlin
// sample: cancellation
fun onAddToCart(scope: CoroutineScope, sdk: SignalDock) {
    scope.launch {
        // If this coroutine is cancelled, track() throws CancellationException. The event may
        // already be stored. Do not catch it and do not call track() again for this action.
        sdk.track("add_to_cart") { property("product_id", "P-001") }
    }
}
// end sample
```

해야 할 일과 하지 말아야 할 일:

- 예외를 위로 전달하세요. `track()`을 `runCatching`이나, 호출을 다시 시작하는 `catch (e: Exception)`으로 감싸지 마세요.
- 같은 동작에 대해 `finally` 블록, 재시도 루프, 새 화면에서 `track()`을 다시 호출하지 마세요.
- 취소 때문에 저장 완료 여부가 불확실하면 SDK가 개발자용 WARN을 남깁니다. `track cancelled during storage; the event may already be stored`. 취소가 저장보다 먼저라고 알 수 있으면 아무것도 남기지 않습니다. 프로세스가 강제 종료되면 로그는 보장되지 않습니다(spec 3, [ADR-0017](adr/0017-sdk-crash-safety.md) 결정 2).
- 닫힌 Room database는 자기 scope를 취소합니다. SDK는 앱의 coroutine이 활성인지 확인합니다. 활성이면 예외가 아니라 `StorageError`를 받습니다(ADR-0017 결정 2).
- `initialize()`를 취소하면 앱의 대기만 멈춥니다(2절).

## 6. 오프라인, 재시도, 큐 한도

### 수집과 전송

1. `track()`은 Room commit 뒤에 `Success`를 반환합니다. 서버 연결이 필요 없습니다(architecture "Commit boundaries").
2. SDK는 저장된 이벤트를 batch로 보냅니다. foreground trigger는 큐에 20개가 쌓이거나, 마지막 전송 시작 이후 처음 저장된 이벤트로부터 10초가 지나면 전송을 시작합니다(spec 4, [ADR-0006](adr/0006-sdk-delivery-decisions.md) 결정 11).
3. 두 번째 경로는 WorkManager입니다. 네트워크가 필요한 unique 일회성 Work 하나와, 복구용 15분 주기 unique Work 하나입니다. Worker 한 번의 실행은 10 batch 또는 30초 뒤에 새 batch 시작을 멈춥니다. batch 사이에 확인합니다(spec 8.A Q57, Q72, ADR-0006 결정 11).
4. SDK는 앱의 WorkManager 설정을 바꾸지 않습니다. WorkManager가 준비되지 않으면 SDK가 WARN을 남기고 foreground 전송은 계속 동작합니다(spec 8.A, ADR-0006 결정 11, ADR-0017 결정 5).
5. 서버는 이벤트를 저장하고 database commit 뒤에 ACK를 반환합니다. SDK는 ACK 전체를 검증한 뒤 확인된 이벤트를 큐에서 지웁니다(architecture "Collection, delivery, and ACK").

WorkManager는 정확한 실행 시각을 약속하지 않습니다(architecture "Agreed defaults, pending measurement").

### 재시도

- SDK는 재전송마다 같은 `eventId`를 유지합니다. ACK를 잃어도 재전송 때 `duplicate` 응답을 받습니다. 행이 두 개가 되지 않습니다(architecture "Lost ACKs and event deduplication").
- 일시적 실패(네트워크, timeout, 408, 429, 501과 505를 제외한 5xx): SDK는 큐를 유지하고 기다립니다. 대기는 기본 10초에서 시작해 두 배씩 늘고 5분에서 멈춥니다. SDK는 0부터 기본값 사이의 무작위 시각을 고르므로(Full Jitter) 거의 바로 재시도할 수도 있습니다. 더 긴 유효한 `Retry-After`가 우선합니다(spec 8.A Q73, Q74, ADR-0006 결정 5와 9).
- 다음 시도 시각은 저장됩니다. 재시작이나 새 이벤트가 대기를 건너뛰게 하지 않습니다. foreground sender와 Worker가 같은 시각을 씁니다(spec 8.A Q74, architecture "Retry and transport boundaries").
- 인증, 설정, 계약 오류(예: 400, 401, 403, 404, redirect): SDK는 일반 전송을 멈추고 큐를 유지하며 30분마다 batch 하나를 시도합니다. 큐가 비어 있으면 아무것도 보내지 않습니다. 유효한 ACK를 받으면 멈춤이 끝납니다(spec 8.B Q58, ADR-0006 결정 5와 8). SDK는 redirect를 따르지 않습니다(architecture "Retry and transport boundaries").
- 이벤트 둘 이상인 batch의 413: SDK가 batch를 나누고 ID를 유지합니다. 이벤트 하나의 413: SDK가 일반 전송을 멈추고 그 이벤트를 30분마다 시도합니다(spec 8.B Q60, ADR-0006 결정 6).
- 잘못된 ACK(읽을 수 없음, 결과 누락, 모순)는 적용하지 않습니다. batch 전체가 남고 SDK는 기다립니다(spec 8.B Q59, architecture "Validate the entire ACK before changing the queue").
- 이벤트 하나의 알려진 영구 거부는 그 이벤트를 큐에서 지웁니다. SDK는 이벤트 내용 없는 diagnostic을 저장합니다(spec 4, architecture "Validate the entire ACK before changing the queue").
- SDK는 실패 횟수만으로 이벤트를 버리지 않습니다(spec 4). 큐 TTL은 없습니다(spec 4).
- 실패 횟수는 유효한 ACK가 적용된 뒤에만 0이 됩니다(spec 8.A Q75).

### 한도

| 한도 | 값 | 출처 |
| --- | --- | --- |
| 이벤트 하나 | 공통 필드를 포함해 전송 JSON 16 KiB | spec 4 |
| batch 하나 | 256 KiB와 이벤트 50개 | spec 4 |
| 큐 | 이벤트 10,000개 또는 전송 JSON 합계 16 MiB | spec 4 |
| 큐 한도에 도달했을 때 | SDK가 기존 이벤트를 유지하고 새 호출에 `QueueFull`을 반환 | spec 4 |
| 큐 TTL | 없음 | spec 4 |
| 영구 거부 diagnostic | 최근 100개 | spec 4 |

큐 예산은 Room 파일 크기의 상한이 아닙니다(spec 4).

### 유지되지 않는 데이터

SDK는 installation ID, 큐, diagnostics, 재시도 상태를 `noBackupFilesDir` 아래 자기 영역에 저장합니다. Android Auto Backup이 복원하지 않습니다(spec 4, [ADR-0001](adr/0001-installation-scoped-identity.md)).

- 같은 설치의 재시작과 일반 업데이트에서는 데이터가 유지됩니다(spec 4).
- 앱 데이터 삭제, 재설치, 지원되는 기기 복원 뒤에는 데이터가 이어지지 않습니다. **아직 보내지 않은 이벤트는 사라질 수 있습니다**(spec 4, architecture "Installation lifecycle and durability").
- SDK는 다른 데이터에 대한 앱의 백업 설정을 바꾸지 않습니다(spec 4).

### 전달 보장

SDK는 서버가 확인할 때까지 이벤트를 다시 보냅니다. exactly-once 전달을 주장하지 않습니다. 서버는 행이 있는 동안 중복 저장을 막습니다. 행을 지우거나 database를 초기화하면 오래된 이벤트가 다시 저장될 수 있습니다(spec 5, architecture "Lost ACKs and event deduplication").

## 7. Diagnostics

`diagnostics()`는 읽기 전용 suspend 호출입니다. 기기 큐의 snapshot을 반환합니다. 서버 상태는 설명하지 않습니다(spec 6 "Demo and diagnostics", architecture "Components and responsibilities").

```kotlin
// sample: diagnostics
suspend fun queueSummary(sdk: SignalDock): String {
    val d = sdk.diagnostics()
    if (!d.storageAvailable) return "queue could not be read" // the other fields mean nothing
    return "queued=${d.queuedEvents} bytes=${d.queuedBytes} " +
        "lastAck=${d.lastAck?.accepted} lastError=${d.lastDeliveryError?.kind} " +
        "failures=${d.retryFailureCount} nextAttempt=${d.nextAttemptEpochMillis}"
}
// end sample
```

| 필드 | 의미 | 출처 |
| --- | --- | --- |
| `sdkVersion` | SDK 릴리스 버전(`0.1.0`). 계약 버전이 아님 | spec 3, spec 8 Q65 |
| `queuedEvents`, `queuedBytes` | 큐에서 기다리는 이벤트와 그 전송 JSON 크기 | spec 6 |
| `oldestPendingEpochMillis` | 큐에서 가장 오래된 이벤트의 수집 시각. 큐가 비면 `null` | spec 6, `Diagnostics` KDoc |
| `recentRejections` | 최근 영구 거부: 이벤트 ID, 코드, 시각. payload와 이름 없음 | spec 4, ADR-0003 결정 7 |
| `lastAck` | 마지막 유효 ACK의 시각과 개수(`accepted`, `duplicate`, `rejected`). 최근 ACK가 큐 전체를 보냈다는 뜻은 아님 | spec 6 |
| `lastDeliveryError` | 마지막 최종 전송 오류: 종류, 선택적 HTTP status, 시각. 복구 뒤에도 기록으로 남음 | spec 6, ADR-0006 결정 4 |
| `retryFailureCount`, `nextAttemptEpochMillis` | 마지막 유효 ACK 이후의 일시적 실패 횟수와 가장 이른 다음 전송 시각 | spec 6, spec 8.A Q75 |
| `storageAvailable` | 큐를 읽지 못하면 `false`. 이때 다른 필드는 비어 있고 의미가 없음. SDK가 WARN도 남김 | ADR-0017 결정 4 |

참고:

- `diagnostics()`는 저장소 실패에서 예외를 던지지 않습니다. `storageAvailable = false`를 반환합니다(ADR-0017 결정 4).
- SDK는 큐, 재시도 상태, 거부 기록을 한 transaction에서 읽으므로 하나의 snapshot입니다(ADR-0003 결정 7).
- 시각은 epoch millisecond(`Long`)입니다. `java.time`은 API 26이 필요하고 `minSdk`는 24이기 때문입니다(ADR-0003 결정 5).
- 화면을 열 때와 수동 새로고침에서 호출하세요. Flow API는 없습니다(spec 6).
- SDK는 서버를 polling하지 않습니다. 서버 기록은 Dashboard에서 봅니다(spec 4, spec 6 "Dashboard").

## 8. 개인정보

- **설치 단위 식별.** SDK는 첫 초기화에서 UUIDv4 installation ID를 하나 만듭니다. 로그인한 사용자와 연결하지 않습니다. 데이터 삭제, 재설치, 지원되는 복원 뒤에는 새 ID가 시작됩니다(ADR-0001).
- 각 이벤트는 수집 시점의 installation ID를 유지합니다. 재전송은 이를 바꾸지 않습니다(ADR-0001).
- SDK에는 `identify` 호출이 없습니다(spec 1).
- SDK는 `SignalDock` tag로 Logcat에 기록합니다. `DEBUG`에서도 이벤트 내용, 이벤트 이름, installation ID, 자격 증명, HTTP body를 기록하지 않습니다(spec 3, architecture "SDK lifecycle and cancellation", spec 8 Q115).
- `TrackResult.Invalid.field`는 기록하지 않습니다(KDoc과 spec 8 Q115).
- collection key는 로컬 평가의 접근을 확인합니다. 키는 앱에서 꺼낼 수 있습니다. 쓰기 전용입니다. 사용자 인증을 대신하지 않습니다(spec 5, architecture "Components and responsibilities").

## 9. 버전과 계약 버전

- **SDK 릴리스 버전:** `0.1.0`. `Diagnostics.sdkVersion`이 반환합니다. 각 이벤트는 수집 시점의 버전을 저장합니다(spec 3 "Common information").
- **계약 버전:** `1`. SDK는 `SignalDock-Contract-Version` header로 보냅니다. 계약 버전과 SDK 릴리스 버전은 별개입니다. 호환되지 않는 변경은 계약 버전을 올립니다. 첫 릴리스는 계약 1만 구현합니다(spec 8.B Q65, Q106, architecture "Contracts, generated sources, and JSON").
- 이벤트는 수집 당시의 계약 버전을 유지합니다. SDK 업데이트 뒤에도 SDK는 큐에 남은 오래된 이벤트를 원래 계약으로, 계약별로 묶은 batch에 담아 보냅니다. 오래된 계약을 끝없이 지원하지는 않습니다(spec 8.B Q66).
- 서버가 그 계약 버전을 지원하지 않으면 요청 전체를 거부합니다. SDK는 큐를 유지하고 30분마다 다시 확인합니다. 스스로 낮추지 않습니다(spec 8.B Q67).
- SDK는 응답의 새 호환 필드를 무시합니다. 알 수 없는 status나 누락된 필드는 ACK 전체를 무효로 만듭니다(spec 8.B Q68).
- consumer 요구 사항(`compileSdk`, Kotlin, AGP)은 [1절](#1-설치)에 있습니다.

## 10. 문제 해결

### SDK가 보고할 수 있는 오류

`initialize()`는 다음 중 하나와 함께 `InitResult.Failure`를 반환합니다(spec 8.B Q112, Q113, Q118, ADR-0017).

| `InitError` | 원인 | 할 일 |
| --- | --- | --- |
| `InvalidConfiguration(message)` | `collectionKey`가 비어 있음, `endpoint`가 host가 있는 절대 `http` 또는 `https` URL이 아님, timeout이 양수이고 유한하지 않음. SDK 상태는 만들어지지 않음 | 설정을 고침. message에는 키가 들어 있지 않음 |
| `ConfigurationConflict` | 다른 설정이 활성이거나 시작 중 | 프로세스 전체에서 설정 하나를 사용 |
| `StorageFailure(message)` | 저장소를 준비하지 못함. `message`는 예외 class 이름 | 나중에 `initialize()`를 다시 호출. 큐와 installation ID는 유지됨 |

`track()`은 `Invalid`, `QueueFull`, `StorageError`를 반환합니다(4절). `diagnostics()`는 저장소 실패를 `storageAvailable = false`로 보고합니다(7절).

전송 오류는 `track()`에 도달하지 않습니다. `Diagnostics.lastDeliveryError.kind`에 나타납니다([ADR-0006](adr/0006-sdk-delivery-decisions.md) 결정 4와 5, architecture "Retry and transport boundaries").

| `DeliveryErrorKind` | 시점 | SDK 동작 | 확인할 것 |
| --- | --- | --- | --- |
| `NETWORK` | 네트워크 오류 | 큐 유지, backoff | 기기 연결, `endpoint` host와 port |
| `TIMEOUT` | call timeout, 408 | 큐 유지, backoff | 서버 속도, `SignalDockConfig`의 timeout |
| `RATE_LIMITED` | 429 | 큐 유지, backoff. `Retry-After` 준수 | 서버 부하 |
| `SERVER_ERROR` | 501과 505를 제외한 5xx | 큐 유지, backoff. `Retry-After` 준수 | 서버 상태 |
| `AUTH_OR_CONFIGURATION` | 401, 403, 404, 415, 기타 4xx, 3xx, 501, 505 | 일반 전송 중지. 30분마다 batch 하나 시도 | `collectionKey`, `endpoint` 경로, redirect |
| `CONTRACT` | 400 | 일반 전송 중지. 30분마다 batch 하나 시도 | SDK와 서버의 계약 버전 |
| `PAYLOAD_TOO_LARGE` | 이벤트 하나의 413 | 일반 전송 중지. 30분마다 그 이벤트 시도 | 서버의 크기 한도 |
| `INVALID_ACK` | 200 응답의 잘못된 ACK, 또는 1xx나 기타 2xx status | 아무것도 적용하지 않고 backoff | 서버 버전과 앱·서버 사이의 proxy |

이벤트 둘 이상인 batch의 413은 오류 종류가 아닙니다. SDK가 batch를 나눕니다(ADR-0006 결정 6).
유효한 `Retry-After`는 모든 행에서 지킵니다(ADR-0006 결정 5).
멈춤은 유효한 ACK 뒤에 끝납니다(ADR-0006 결정 8).

### 로그 tag와 메시지

tag는 `SignalDock`입니다. 기본 수준은 `WARN`이므로 기본값에서는 WARN과 ERROR만 보입니다(spec 8 Q115, `LogLevel`). 메시지는 고정 문구이며 이벤트 내용이 없습니다(ADR-0017 결정 2, 소스의 `Logger`).

| 수준 | 메시지 | 의미 |
| --- | --- | --- |
| WARN | `track cancelled during storage; the event may already be stored` | [5절](#5-취소) 참고. 다시 수집하지 마세요 |
| WARN | `track storage failed: <ExceptionClass>` | `track()`이 `StorageError`를 반환함 |
| WARN | `diagnostics storage failed: <ExceptionClass>` | `diagnostics()`가 `storageAvailable = false`를 반환함 |
| WARN | `WorkManager request failed: <ExceptionClass>; foreground delivery still works` | WorkManager가 준비되지 않았거나 실패함. foreground 전송은 동작함 |
| WARN | `delivery run failed: <ExceptionClass>` | 전송 실행이 실패함. 큐는 유지됨 |
| WARN | `delivery paused by a configuration, authentication or contract error; rechecking every 30 minutes` | `AUTH_OR_CONFIGURATION`과 `CONTRACT` 참고. 멈춤이 시작될 때 한 번 남김 |
| WARN | `delivery resumed after a successful ACK` | 멈춤이 끝남 |
| WARN | `installation id file is invalid; generating a new id` | 저장된 ID가 UUIDv4가 아니었음. SDK가 새 ID를 만듦(ADR-0003 결정 6) |
| DEBUG | `initialized`, `delivery attempt started`, `delivery attempt finished in <n> ms`, `delivery failed: <kind>` | 정상 진행. `LogLevel.DEBUG`로 설정하면 보임 |

`LogLevel.DEBUG`는 디버깅할 때만 쓰세요. `LogLevel.NONE`은 아무것도 기록하지 않습니다.

### 자주 하는 질문

- **`track()`이 `Success`를 반환했는데 Dashboard에 이벤트가 없습니다.** `Success`는 "기기에 저장됨"입니다. `diagnostics()`를 읽으세요. `queuedEvents`, `lastDeliveryError`, `nextAttemptEpochMillis`가 이벤트가 기다리는 이유를 보여 줍니다(spec 6).
- **`lastAck`는 오래됐는데 큐가 비어 있지 않습니다.** 최근 ACK가 큐 전체를 보냈다는 뜻은 아닙니다(spec 6).
- **재설치 뒤에 이벤트가 없습니다.** 보내지 않은 이벤트는 데이터 삭제나 재설치에서 유지되지 않습니다([6절](#유지되지-않는-데이터)).
- **동작 하나에 이벤트가 둘입니다.** 취소 뒤의 두 번째 `track()` 호출이 있는지 확인하세요([5절](#5-취소)).
- **WorkManager WARN이 보입니다.** 앱의 WorkManager가 준비되지 않았습니다. foreground 전송은 동작합니다(ADR-0006 결정 11).
- **빌드 오류 "incompatible version of Kotlin" 또는 AGP 오류.** [1절](#1-설치)의 요구 사항을 보세요.
- **프로세스가 둘.** SDK는 기본 프로세스용입니다. 프로세스 둘이 SDK를 실행하면 같은 이벤트를 둘 다 보낼 수 있습니다. 서버는 `duplicate`로 답합니다. 안전하지만 낭비입니다(ADR-0006 결정 10).

## 11. SDK가 하지 않는 일

다음은 첫 릴리스의 범위 밖입니다(spec 1 "Goal and scope").

- iOS와 Flutter SDK 없음.
- React Native, Flutter, Unity bridge 없음. native와 web 이벤트를 잇는 WebView JavaScript bridge 없음(Q150).
- 자동 이벤트 없음. 앱이 `track()`을 호출합니다.
- `identify`와 사용자 식별 없음.
- 캠페인과 push 없음, funnel 분석 없음.
- 여러 프로젝트와 계정 없음.
- 공개 인터넷 운영과 Maven Central 배포 없음.

SDK는 또한 다음을 하지 않습니다.

- 서버가 이벤트를 가졌다고 말하지 않습니다. `Success`는 기기 저장만 뜻합니다(spec 3).
- 앱이 `eventId`나 `occurredAt`을 정하게 하지 않습니다(spec 3).
- 앱 대신 큐 항목을 지우거나, 이벤트 내용을 보여 주거나, diagnostics Flow를 제공하지 않습니다(spec 6).
- 서버 database를 polling하지 않습니다(spec 4).
- 입력을 trim, 정규화, 수정하지 않습니다(spec 3, spec 8 Q64, Q84).
- `ViewModel`이나 Compose를 쓰지 않습니다(spec 2).
- exactly-once 전달을 주장하지 않습니다(spec 5).
- 사용자 인증을 제공하지 않습니다. collection key는 비밀이 아닙니다(spec 5).
- 성능을 약속하지 않습니다. 첫 릴리스는 처리량을 보장하지 않습니다(spec 7).

## 12. 코드 샘플

이 가이드의 샘플은 [`IntegrationGuideSamplesTest.kt`](../sdk/sdk-android/src/test/kotlin/local/signaldock/IntegrationGuideSamplesTest.kt)에서 복사했습니다. 이 파일의 `sample:`과 `end sample` 표시 사이의 텍스트가 이 가이드의 텍스트입니다. 이 파일은 SDK와 함께 컴파일되며, `cd sdk && ./gradlew test`가 track과 diagnostics 샘플을 실제 Room database에서 실행합니다.

Maven ZIP의 SDK를 쓰는 완전한 앱은 [consumer 앱](../compatibility/consumer-android/)입니다. [`ConsumerApplication.kt`](../compatibility/consumer-android/app/src/main/kotlin/local/signaldock/consumer/ConsumerApplication.kt)와 [`MainActivity.kt`](../compatibility/consumer-android/app/src/main/kotlin/local/signaldock/consumer/MainActivity.kt)를 보세요(ADR-0009 결정 3).
