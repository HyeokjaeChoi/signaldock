# SDK 통합 가이드

[English](integration-guide.md)

이 가이드는 앱 개발자가 SignalDock Android SDK를 앱에 추가하고 사용하는 방법을 설명합니다. iOS SDK는 [13절](#13-ios-sdk)에서 다룹니다.
새로운 동작은 추가하지 않습니다. 모든 문장은 출처를 밝힙니다. 출처는 [spec](../.scratch/signaldock/spec.md)("spec 3"은 3절), [architecture](../architecture.md), [ADR](adr/)입니다.
SDK의 모든 public 선언에는 KDoc이 있습니다. KDoc은 이 가이드와 일치합니다.

SDK 패키지는 `local.signaldock`, 릴리스 버전은 `0.4.0`, 좌표는 `local.signaldock:sdk-android:0.4.0`입니다(spec 8.C, Q126).

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
13. [iOS SDK](#13-ios-sdk)

## 1. 설치

SDK는 ZIP으로 묶은 로컬 Maven repository 폴더입니다. Maven Central에는 없습니다(spec 1, spec 6 "Reproduction and deliverables", [ADR-0009](adr/0009-maven-zip-and-consumer-app.md) 결정 1).

1. 저장소 루트에서 ZIP을 만듭니다(ADR-0009 결정 2).

   ```sh
   sdk/gradlew -p sdk :sdk-android:signaldockMavenZip
   ```

   결과 파일은 `sdk/sdk-android/build/distributions/signaldock-sdk-android-0.4.0-maven.zip`입니다.
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
dependencies { implementation("local.signaldock:sdk-android:0.4.0") }
```

0.4.0 AAR의 요구 사항:

- `compileSdk` 36 이상, `minSdk` 24 이상([ADR-0006](adr/0006-sdk-delivery-decisions.md) 결정 1, spec 2).
- Kotlin 2.2 이상과 AGP 9.x. SDK 클래스 metadata가 2.2이므로 AGP 9의 기본 built-in Kotlin으로 동작합니다(ADR-0009 결정 4, Q132). AGP 8.x 앱은 0.4.0을 쓸 수 없습니다(ADR-0009 결정 4).
- 앱이 `INTERNET` 권한을 선언해야 합니다(consumer 앱의 [manifest](../compatibility/consumer-android/app/src/main/AndroidManifest.xml)에 있습니다. [README](../README.md) "Add the SDK to an app"). SDK 자체의 R8 규칙은 필요 없습니다(ADR-0009 결정 6).
- `https://` endpoint를 쓰세요. SDK는 `http://`를 `https://`로 바꾸지 않습니다. config에 `allowInsecureEndpoint = true`(기본값 `false`, ADR-0035)가 없으면 `initialize`는 `http://` endpoint를 `InvalidConfiguration`으로 거부합니다. `http://` 로컬 서버에만 설정하세요. `http://` 로컬 서버를 쓰려면 앱이 그 host에 cleartext를 허용해야 합니다. consumer 앱은 `10.0.2.2`, `127.0.0.1`, `localhost`에만 허용합니다(ADR-0009 결정 5, Q134).

## 2. 초기화

`Application`에서 `SignalDock.initialize()`를 한 번 호출합니다(spec 3 "Lifetime and cancellation").
suspend 함수입니다. 저장소 준비는 메인 스레드 밖에서 합니다(architecture "SDK lifecycle and cancellation").
SDK는 application context만 보관합니다(spec 3).

```kotlin
public suspend fun initialize(context: Context, config: SignalDockConfig): InitResult
```

`SignalDockConfig`에는 `endpoint`와 `collectionKey`가 있습니다. 선택 항목으로 `logLevel`(기본값 `WARN`), timeout 네 개, `allowInsecureEndpoint`(기본값 `false`, 1절의 endpoint 규칙 참고)가 있습니다. 기본값은 call 30초, connect/read/write 각 10초입니다. timeout은 양수이고 유한해야 합니다(architecture "Agreed defaults, pending measurement", spec 8 Q117). 설정은 SDK 수명 동안 고정입니다(spec 3). `toString()`은 collection key를 숨깁니다(spec 8 Q115).

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
        TrackResult.OptedOut -> "collection is off" // nothing stored
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
- SDK가 `eventId`(UUIDv4), `installationId`, `occurredAt`(`track()`이 호출을 처리한 기기 시각), `sdkVersion`, `sdkPlatform`을 추가합니다(`sdkPlatform`은 0.4.0부터 `"android"`이며 iOS SDK는 `"ios"`를 보냅니다. [ADR-0046](adr/0046-sdk-platform-field.md)). 앱은 `eventId`와 `occurredAt`을 정할 수 없습니다(spec 3 "Common information").

### 선택 사항: tracking plan과 생성된 함수

이벤트를 plan 파일에 적고, 그 파일에서 타입이 있는 함수를 생성할 수 있습니다. 선택 사항입니다. SDK와 공개 API는 바뀌지 않으며, 기존 `track("name") { property(...) }`도 그대로 동작합니다(spec Q153).

이 저장소는 `tracking-plan/`에서 이 방식을 보여 줍니다([ADR-0021](adr/0021-tracking-plan-codegen.md)). `tracking-plan/plan.json`에 이벤트마다 property, property 타입, 필수 여부를 적습니다. `node tracking-plan/generate.mjs`가 Kotlin 파일과 TypeScript 파일을 생성합니다. 생성된 Kotlin 함수는 `track()`만 호출합니다. 필수 property는 null이 아닌 파라미터이고, 선택 property는 `null`이면 빠지는 nullable 파라미터입니다.

```kotlin
// Generated from tracking-plan/plan.json (demo app). Not part of the SDK.
sdk.productViewed(productId = "P-001")                      // price_minor left out
sdk.productViewed(productId = "P-001", priceMinor = 59_000)

// The same event with plain track():
sdk.track("product_viewed") { property("product_id", "P-001") }
```

TypeScript 출력에는 타입과, `{ name, properties }`를 반환하는 builder 함수가 있습니다. SDK는 plan을 검사하지 않습니다. 서버는 검사할 수 있습니다. 서버에 plan이 있으면(`SIGNALDOCK_TRACKING_PLAN`, Compose가 설정) 저장한 이벤트마다 `planned`, `unplanned_event`, `invalid_properties` 중 하나로 표시합니다. plan 때문에 이벤트를 거부하거나 버리지 않으며 ACK도 바뀌지 않습니다(spec Q155, [ADR-0022](adr/0022-server-tracking-plan-status.md)). Dashboard가 상태와 위반 건수를 보여 줍니다.

## 4. TrackResult의 모든 경우 처리

| 결과 | 의미 | 앱이 할 일 | 출처 |
| --- | --- | --- | --- |
| `Success(eventId)` | 이벤트가 기기에 저장됨. 서버 수신이 아님 | 없음. SDK가 나중에 보냄 | spec 3, architecture "Commit boundaries" |
| `Invalid(reason, field)` | 입력이 규칙을 어김. 저장된 것 없음. `field`는 `"name"` 또는 property key. `EVENT_TOO_LARGE`이면 `null` | 코드의 호출을 고침. 같은 입력을 다시 보내지 않음 | spec 3, spec 8 Q63 |
| `OptedOut` | 앱이 `setOptOut(true)`를 호출함. 검증도 저장도 하지 않음 | 없음. 수집을 의도적으로 껐음. [8절](#8-개인정보) 참고 | spec Q156, [ADR-0034](adr/0034-sdk-opt-out.md) |
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
- 서버 운영자는 이벤트를 이름이나 SDK 버전으로 차단하거나 모든 수집을 멈출 수 있습니다. 차단된 이벤트는 이벤트별 영구 거부(code `COLLECTION_BLOCKED`)로 돌아오며, SDK는 그 이벤트를 큐에서 지우고 `server_rejected`로 셉니다. 멈춘 서버는 `Retry-After`가 있는 503으로 응답하므로 SDK는 큐를 유지하고 기다립니다. SDK는 전송 전에는 차단 사실을 모르고 계속 수집합니다. 차단된 이벤트는 한 번 전송한 뒤 큐에서 나가지만, 서버가 멈춰 있거나 SDK의 전송 속도보다 빠르게 수집하면 큐가 `QueueFull`까지 찰 수 있습니다(spec Q165, [ADR-0037](adr/0037-server-collection-kill-switch.md)).
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
| `sdkVersion` | SDK 릴리스 버전(`0.4.0`). 계약 버전이 아님 | spec 3, spec 8 Q65 |
| `queuedEvents`, `queuedBytes` | 큐에서 기다리는 이벤트와 그 전송 JSON 크기 | spec 6 |
| `oldestPendingEpochMillis` | 큐에서 가장 오래된 이벤트의 수집 시각. 큐가 비면 `null` | spec 6, `Diagnostics` KDoc |
| `recentRejections` | 최근 영구 거부: 이벤트 ID, 코드, 시각. payload와 이름 없음 | spec 4, ADR-0003 결정 7 |
| `lastAck` | 마지막 유효 ACK의 시각과 개수(`accepted`, `duplicate`, `rejected`). 최근 ACK가 큐 전체를 보냈다는 뜻은 아님 | spec 6 |
| `lastDeliveryError` | 마지막 최종 전송 오류: 종류, 선택적 HTTP status, 시각. 복구 뒤에도 기록으로 남음 | spec 6, ADR-0006 결정 4 |
| `retryFailureCount`, `nextAttemptEpochMillis` | 마지막 유효 ACK 이후의 일시적 실패 횟수와 가장 이른 다음 전송 시각 | spec 6, spec 8.A Q75 |
| `contractDeprecation` | `null`, 또는 이 process에 대한 서버의 폐기 예고: `deprecatedAtEpochMillis`, `sunsetAtEpochMillis`(각각 `null` 가능). SDK는 `Deprecation`, `Sunset` 응답 헤더를 읽고 process당 WARN을 한 번 남김. 전송 동작은 바뀌지 않음. 메모리에만 있고 `copy()`, `equals()`, `componentN()`에는 없음 | spec 8 Q159, ADR-0030 |
| `storageAvailable` | 큐를 읽지 못하면 `false`. 이때 다른 필드는 비어 있고 의미가 없음. SDK가 WARN도 남김 | ADR-0017 결정 4 |

참고:

- `diagnostics()`는 저장소 실패에서 예외를 던지지 않습니다. `storageAvailable = false`를 반환합니다(ADR-0017 결정 4).
- SDK는 큐, 재시도 상태, 거부 기록을 한 transaction에서 읽으므로 하나의 snapshot입니다(ADR-0003 결정 7).
- 시각은 epoch millisecond(`Long`)입니다. `java.time`은 API 26이 필요하고 `minSdk`는 24이기 때문입니다(ADR-0003 결정 5).
- 화면을 열 때와 수동 새로고침에서 호출하세요. Flow API는 없습니다(spec 6).
- SDK는 서버를 polling하지 않습니다. 서버 기록은 Dashboard에서 봅니다(spec 4, spec 6 "Dashboard").

### 버려진 이벤트 수 (client report)

SDK는 버린 이벤트를 이유별로 세고, 그 수를 `POST /v1/client-reports`로 서버에 보냅니다. 새 public API와 설정은 없습니다. 이유는 `queue_full`(`TrackResult.QueueFull`), `storage_error`(`TrackResult.StorageError`), `invalid_event`와 `event_too_large`(`TrackResult.Invalid`), `server_rejected`(유효한 ACK의 영구 거부)입니다. opt-out은 세지 않습니다. 카운터는 Room에 저장하므로 재시작해도 사라지지 않고, 각 카운터는 1,000,000에서 멈춥니다.

- SDK는 전송 주기마다 이벤트 batch 뒤에 report를 최대 한 번 보냅니다. 그 주기에서 유효한 ACK를 적용했고 카운터가 0이 아닐 때만 보냅니다. report에는 SDK 버전과 수만 있습니다. installation ID, 이벤트 이름, 이벤트 ID는 없습니다(spec Q164, [ADR-0036](adr/0036-sdk-client-reports.md)).
- 2xx 응답은 보낸 수를 지웁니다. 404(오래된 서버), 408과 429를 제외한 다른 4xx, 3xx, 1xx, 501, 505는 재시도 없이 지웁니다. 네트워크 오류, 408, 429, 501과 505를 제외한 5xx는 다음 주기를 위해 유지합니다. report는 이벤트의 재시도 상태를 바꾸지 않습니다.
- 수는 근사값입니다. 서버가 report를 저장했는데 응답이 사라지면 SDK는 같은 수를 다시 보냅니다. SDK가 storage에 쓰지 못한 drop은 세지 않습니다.

## 8. 개인정보

- **설치 단위 식별.** SDK는 첫 초기화에서 UUIDv4 installation ID를 하나 만듭니다. 로그인한 사용자와 연결하지 않습니다. 데이터 삭제, 재설치, 지원되는 복원 뒤에는 새 ID가 시작됩니다(ADR-0001).
- 각 이벤트는 수집 시점의 installation ID를 유지합니다. 재전송은 이를 바꾸지 않습니다(ADR-0001).
- SDK에는 `identify` 호출이 없습니다(spec 1).
- SDK는 `SignalDock` tag로 Logcat에 기록합니다. `DEBUG`에서도 이벤트 내용, 이벤트 이름, installation ID, 자격 증명, HTTP body를 기록하지 않습니다(spec 3, architecture "SDK lifecycle and cancellation", spec 8 Q115).
- `TrackResult.Invalid.field`는 기록하지 않습니다(KDoc과 spec 8 Q115).
- **옵트아웃.** 사용자가 동의를 철회하는 등 수집을 멈추려면 `setOptOut(true)`를 호출합니다. 이후 `track()`은 아무것도 저장하지 않고 `OptedOut`을 반환합니다. 이 호출들의 이벤트는 전송되지 않습니다. `setOptOut(false)`는 수집을 다시 시작합니다. `isOptedOut()`은 현재 상태를 반환합니다(spec Q156, [ADR-0034](adr/0034-sdk-opt-out.md)).

  ```kotlin
  // sample: opt-out
  suspend fun onUserWithdrawsConsent(sdk: SignalDock): Boolean {
      // false: the state could not be stored. It holds until the process ends, but after a
      // restart collection can resume, so call setOptOut(true) again later or tell the user.
      val stored = sdk.setOptOut(true) // from now on, track() stores nothing and returns OptedOut
      // Optional: delete events that wait on the device. null: storage failed, the events are
      // still queued, so call clearQueue() again later or tell the user.
      val deleted = sdk.clearQueue()
      return stored && deleted != null
  }

  suspend fun onUserGivesConsent(sdk: SignalDock): Boolean =
      // Collection resumes at once. false: the state could not be stored, so after a restart
      // collection is off again; call setOptOut(false) again later or tell the user.
      sdk.setOptOut(false)
  // end sample
  ```

  - **이미 큐에 있는 이벤트는 남고, SDK는 계속 전송합니다.** 옵트아웃은 sender를 멈추지 않습니다. 큐의 이벤트를 지우려면 `clearQueue()`를 호출합니다. 지운 개수를 반환하며, 저장소 실패 시 `null`입니다. 그 순간 전송 중인 batch는 서버에 도달할 수 있습니다.
  - 상태는 재시작 뒤에도 유지됩니다. `noBackupFilesDir`의 SDK 디렉터리에 있으므로 Auto Backup이 복원하지 않습니다. 재설치나 기기 복원 뒤에는 수집이 다시 켜집니다.
  - 상태를 디스크에 저장하지 못하면 `setOptOut`은 `false`를 반환합니다. 변경은 프로세스가 끝날 때까지 유지됩니다.
  - `initialize`가 성공하면 바로 `setOptOut`을 호출할 수 있습니다. SDK는 `initialize` 중에 저장된 상태를 읽으므로 첫 `track()`도 이를 따릅니다.
  - `setOptOut(true)`가 반환되기 전에 시작된 `track()` 호출은 이벤트를 저장할 수 있습니다.
  - 서버가 이미 가진 이벤트는 지우지 않습니다(ADR-0028).
- collection key는 로컬 평가의 접근을 확인합니다. 키는 앱에서 꺼낼 수 있습니다. 쓰기 전용입니다. 사용자 인증을 대신하지 않습니다(spec 5, architecture "Components and responsibilities").

## 9. 버전과 계약 버전

- **SDK 릴리스 버전:** `0.4.0`. `Diagnostics.sdkVersion`이 반환합니다. 각 이벤트는 수집 시점의 버전을 저장합니다(spec 3 "Common information").
- **계약 버전:** `1`. SDK는 `SignalDock-Contract-Version` header로 보냅니다. 계약 버전과 SDK 릴리스 버전은 별개입니다. 호환되지 않는 변경은 계약 버전을 올립니다. 첫 릴리스는 계약 1만 구현합니다(spec 8.B Q65, Q106, architecture "Contracts, generated sources, and JSON").
- 이벤트는 수집 당시의 계약 버전을 유지합니다. SDK 업데이트 뒤에도 SDK는 큐에 남은 오래된 이벤트를 원래 계약으로, 계약별로 묶은 batch에 담아 보냅니다. 오래된 계약을 끝없이 지원하지는 않습니다(spec 8.B Q66). 새 계약이 나온 뒤 서버는 이전 계약을 최소 12개월, 그리고 트래픽이 낮아질 때까지 유지하고 `Deprecation`, `Sunset` 헤더로 알릴 계획입니다. 서버 쪽은 아직 만들지 않았습니다. SDK는 이 헤더를 이미 읽습니다(spec 8 Q159, [ADR-0030](adr/0030-sdk-contract-deprecation-warning.md)).
- 서버가 그 계약 버전을 지원하지 않으면 요청 전체를 거부합니다. SDK는 큐를 유지하고 30분마다 다시 확인합니다. 스스로 낮추지 않습니다(spec 8.B Q67).
- SDK는 응답의 새 호환 필드를 무시합니다. 알 수 없는 status나 누락된 필드는 ACK 전체를 무효로 만듭니다(spec 8.B Q68).
- consumer 요구 사항(`compileSdk`, Kotlin, AGP)은 [1절](#1-설치)에 있습니다.

## 10. 문제 해결

### SDK가 보고할 수 있는 오류

`initialize()`는 다음 중 하나와 함께 `InitResult.Failure`를 반환합니다(spec 8.B Q112, Q113, Q118, ADR-0017).

| `InitError` | 원인 | 할 일 |
| --- | --- | --- |
| `InvalidConfiguration(message)` | `collectionKey`가 비어 있음, `endpoint`가 host가 있는 절대 `https` URL이 아님(`http` URL은 `allowInsecureEndpoint = true`일 때만 허용), timeout이 양수이고 유한하지 않음. SDK 상태는 만들어지지 않음 | 설정을 고침. message에는 키가 들어 있지 않음 |
| `ConfigurationConflict` | 다른 설정이 활성이거나 시작 중 | 프로세스 전체에서 설정 하나를 사용 |
| `StorageFailure(message)` | 저장소를 준비하지 못함. `message`는 예외 class 이름 | 나중에 `initialize()`를 다시 호출. 큐와 installation ID는 유지됨 |

`track()`은 `Invalid`, `OptedOut`, `QueueFull`, `StorageError`를 반환합니다(4절). `diagnostics()`는 저장소 실패를 `storageAvailable = false`로 보고합니다(7절).

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
유효한 `Retry-After`는 HTTP 응답에서 나온 모든 행에서 지킵니다(ADR-0006 결정 5).
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
| WARN | `server contract version is deprecated; sunset date: <date>` | 응답에 유효한 `Deprecation`이나 `Sunset` header가 있었음. 프로세스당 한 번. `Diagnostics.contractDeprecation` 참고(ADR-0030) |
| WARN | `drop counter failed: <ExceptionClass>` | SDK가 drop 카운터를 갱신하지 못함. 그 drop은 세지 않음 |
| WARN | `client report failed: <ExceptionClass>` | client report 실행이 실패함. 카운터는 유지됨 |
| WARN | `opt-out state could not be stored` | `setOptOut`이 상태를 storage에 쓰지 못함 |
| WARN | `clearQueue storage failed: <ExceptionClass>` | `clearQueue()`가 큐의 이벤트를 지우지 못함 |
| DEBUG | `initialized`, `delivery attempt started`, `delivery attempt finished in <n> ms`, `delivery failed: <kind>` | 정상 진행. `LogLevel.DEBUG`로 설정하면 보임 |
| DEBUG | `opt-out set`, `client report: <SENT, DISCARDED, KEPT 중 하나>` | opt-out 상태를 저장함. client report 결과(7절 참고) |

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

- Flutter SDK 없음. iOS SDK는 [13절](#13-ios-sdk)에 있습니다.
- React Native, Flutter, Unity bridge 없음. native와 web 이벤트를 잇는 WebView JavaScript bridge 없음(Q150).
- 자동 이벤트 없음. 앱이 `track()`을 호출합니다.
- `identify`와 사용자 식별 없음.
- 캠페인과 push 없음, funnel 분석 없음.
- 여러 프로젝트와 계정 없음.
- 공개 인터넷 운영과 Maven Central 배포 없음.

SDK는 또한 다음을 하지 않습니다.

- 서버가 이벤트를 가졌다고 말하지 않습니다. `Success`는 기기 저장만 뜻합니다(spec 3).
- 앱이 `eventId`나 `occurredAt`을 정하게 하지 않습니다(spec 3).
- 큐 항목을 하나씩 지우거나, 이벤트 내용을 보여 주거나, diagnostics Flow를 제공하지 않습니다(spec 6). 큐 전체만 지울 수 있습니다(`clearQueue()`, spec Q156).
- 서버 database를 polling하지 않습니다(spec 4).
- 입력을 trim, 정규화, 수정하지 않습니다(spec 3, spec 8 Q64, Q84).
- `ViewModel`이나 Compose를 쓰지 않습니다(spec 2).
- exactly-once 전달을 주장하지 않습니다(spec 5).
- 사용자 인증을 제공하지 않습니다. collection key는 비밀이 아닙니다(spec 5).
- 성능을 약속하지 않습니다. 첫 릴리스는 처리량을 보장하지 않습니다(spec 7).

## 12. 코드 샘플

이 가이드의 샘플은 [`IntegrationGuideSamplesTest.kt`](../sdk/sdk-android/src/test/kotlin/local/signaldock/IntegrationGuideSamplesTest.kt)에서 복사했습니다. 이 파일의 `sample:`과 `end sample` 표시 사이의 텍스트가 이 가이드의 텍스트입니다. 이 파일은 SDK와 함께 컴파일되며, `cd sdk && ./gradlew test`가 track과 diagnostics 샘플을 실제 Room database에서 실행합니다.

Maven ZIP의 SDK를 쓰는 완전한 앱은 [consumer 앱](../compatibility/consumer-android/)입니다. [`ConsumerApplication.kt`](../compatibility/consumer-android/app/src/sdk/kotlin/local/signaldock/consumer/ConsumerApplication.kt)와 [`MainActivity.kt`](../compatibility/consumer-android/app/src/sdk/kotlin/local/signaldock/consumer/MainActivity.kt)를 보세요(ADR-0009 결정 3).

## 13. iOS SDK

iOS SDK의 동작은 Android SDK 0.3.0과 같습니다(ADR-0047 D1). 이 절의 차이를 두고 3~6절, 8절, 9절이 iOS에도 적용됩니다. 7절(diagnostics)은 한 가지가 다릅니다. iOS에서는 `contractDeprecation`이 `Diagnostics`의 자동 생성 `==`에 포함됩니다. 10절의 오류 종류와 전송 표는 적용되지만 로그 tag와 메시지는 적용되지 않으며(아래 로그 참고), `StorageFailure`는 코드를 담습니다(로그 참고). 1절은 아래 설치가 대신합니다. Swift 타입은 `SignalDockSDK`이고 모듈은 `SignalDock`입니다. 버전은 `0.1.0`입니다(`SignalDockSDK.version`). public API는 `sdk-ios/api/SignalDock.swiftinterface`에 있고, 선언에는 DocC 주석이 있습니다.

**상태.** iOS 릴리스는 아직 없습니다. 첫 tag는 `ios-v0.1.0`이며 tag push에는 owner의 승인이 필요합니다(ADR-0046 결정 8, spec 4절). 그 전에는 ZIP을 직접 빌드하세요([reproduce.md](reproduce.md#ios-release-zip-and-consumer-app) 참고).

### 설치

1. GitHub Release `ios-vX.Y.Z`에서 `signaldock-sdk-ios-X.Y.Z.zip`과 `signaldock-sdk-ios-X.Y.Z.zip.sha256`을 내려받습니다. 두 파일이 있는 폴더에서 `shasum -a 256 -c signaldock-sdk-ios-X.Y.Z.zip.sha256`으로 해시를 확인합니다. 릴리스에는 build provenance attestation도 붙습니다(`gh attestation verify signaldock-sdk-ios-X.Y.Z.zip --repo HyeokjaeChoi/signaldock --signer-workflow HyeokjaeChoi/signaldock/.github/workflows/release-ios.yml`). Gap: 이 명령은 아직 iOS ZIP에서 실행하지 않았습니다(ADR-0047 ticket 13 결과).
2. 압축을 풉니다. ZIP에는 폴더 하나, `SignalDock/`이 있고 그 안에 `Package.swift`, `Sources/`, `LICENSE`가 있습니다(ADR-0047 ticket 13 결과).
3. Xcode에서 File, Add Package Dependencies, Add Local을 선택하고 그 폴더를 고릅니다. 앱 target에 product `SignalDock`을 추가합니다. `compatibility/consumer-ios/`가 local package reference로 같은 일을 합니다.

요구 사항:

- iOS 15 이상. package manifest가 `swift-tools-version: 6.0`을 쓰므로 Swift 6 이상의 Xcode가 필요합니다(`sdk-ios/Package.swift`). Xcode 26.6(Swift 6.3, CI toolchain)과 Xcode 27.1(Swift 6.4, 로컬 실행)에서 테스트했습니다. Gap: 더 오래된 Xcode와 Swift 버전은 테스트하지 않았습니다.
- 외부 의존성 없음(ADR-0047 D7, D13). SDK는 시스템 SQLite를 씁니다.
- 앱 전용. public 진입점에 `@available(iOSApplicationExtension, unavailable)`가 붙어 있습니다(ADR-0047 D9).
- `https://` endpoint를 쓰세요. `http://` endpoint는 `allowInsecureEndpoint: true`가 필요하고, App Transport Security도 그 호스트를 허용해야 합니다(ADR-0035). consumer 앱은 로컬 서버를 위해 `NSAllowsLocalNetworking`을 설정합니다.
- package는 `PrivacyInfo.xcprivacy`를 resource로 포함합니다(ADR-0047 D14).

### 초기화와 수집

`SignalDockConfig`의 필드는 Android와 같습니다. timeout은 초 단위 `TimeInterval`입니다(기본값은 call 30초, connect, read, write 각 10초). `logLevel` 기본값은 `.warn`입니다. `initialize(_:)`는 `async`입니다. 던지는 것은 `CancellationError`뿐입니다(ADR-0047 ticket 08 결과, Q114).

```swift
import SignalDock

func startSDK() async -> SignalDockSDK? {
    let config = SignalDockConfig(endpoint: "https://events.example.com", collectionKey: "your-collection-key")
    do {
        switch try await SignalDockSDK.initialize(config) {
        case .success(let sdk):
            return sdk
        case .failure(let error):
            // invalidConfiguration, configurationConflict, storageFailure: 10절 참고.
            print("SignalDock not ready: \(error)")
            return nil
        }
    } catch {
        return nil // CancellationError: 호출자가 취소됨
    }
}

func onProductViewed(_ sdk: SignalDockSDK, productId: String) async {
    let result = try? await sdk.track("product_viewed") {
        $0.property("product_id", productId)
        $0.property("price_minor", Int64(1999))
    }
    // result가 nil이면 호출자가 취소된 경우뿐입니다. 이벤트는 그래도 저장되었을 수 있습니다.
    _ = result
}
```

- `initialize(_:)`는 실행 시작 때 한 번 호출합니다. 예를 들어 `App`이나 `AppDelegate`에서 시작한 `Task`에서 호출합니다. 같은 설정의 호출은 한 번의 시도를 공유합니다. 다른 설정은 `configurationConflict`를 반환합니다.
- `track(isolation:_:_:)`는 이벤트 이름과 builder closure를 받습니다. builder에는 `String`, `Bool`, `Int`, `Int64`, `Double`용 `property(_:_:)`가 있습니다. ±(2^53 - 1) 밖의 정수는 `invalid(reason: .numberOutOfRange, ...)`를 반환합니다.
- 이름과 property 규칙은 Android와 같습니다(3절). Swift 때문에 생기는 차이가 둘 있습니다(ADR-0047 ticket 08 결과). Swift `String`은 짝 없는 surrogate를 담을 수 없어서 그 `forbiddenCharacter` 경우는 생기지 않습니다. Swift가 같다고 보는 key(`é`를 한 code point로 쓴 것과 `e` + U+0301로 쓴 것)는 한 key이므로 호출은 `duplicateKey`를 반환합니다.
- tracking plan 생성기는 Swift도 만듭니다(`tracking-plan/generated/swift/TrackingPlan.swift`, ADR-0047 ticket 11 amendment). 이벤트마다 `SignalDockSDK` extension에 타입이 있는 함수를 하나 추가합니다.

### 결과: `default`를 쓰세요

`TrackResult`의 경우는 `success(eventId:)`, `invalid(reason:field:)`, `optedOut`, `queueFull`, `storageError(message:)`입니다.

**minor 버전은 public 결과 enum에 case를 추가할 수 있습니다**(`TrackResult`, `InvalidReason`, `InitError`, diagnostics enum). 이 enum은 `@frozen`이 아닙니다. Android도 0.2.0에서 `OptedOut`을 추가했습니다(ADR-0047 2026-10-28 amendment, "enum evolution"). 이 enum을 `switch`할 때는 항상 `default` 분기를 두세요. 새 case가 생겨도 빌드가 깨지지 않습니다.

```swift
func describe(_ result: TrackResult) -> String {
    switch result {
    case .success(let eventId): return "stored \(eventId)"
    case .optedOut: return "collection is off"
    case .queueFull: return "queue is full"
    default: return "not stored: \(result)" // invalid, storageError, 이후 버전이 추가하는 case
    }
}
```

### Opt-out

`setOptOut(_:)`는 `async`이고, 상태를 저장하지 못하면 `false`를 반환합니다. `isOptedOut()`은 동기 함수입니다. `clearQueue()`는 지운 이벤트 수를 반환하고, 저장소가 실패하면 `nil`을 반환합니다. 규칙은 Android와 같습니다(8절, ADR-0034). 큐에 있는 이벤트는 남고, `clearQueue()`를 호출하기 전까지 계속 전송됩니다. 상태는 `signaldock/` 디렉터리의 marker 파일이며, 이 디렉터리는 백업에서 제외됩니다.

```swift
func onUserWithdrawsConsent(_ sdk: SignalDockSDK) async throws -> Bool {
    let stored = await sdk.setOptOut(true) // 이제 track()은 아무것도 저장하지 않고 .optedOut을 반환합니다
    let deleted = try await sdk.clearQueue() // 선택 사항. nil은 저장소 실패
    return stored && deleted != nil
}
```

### Diagnostics

`diagnostics()`는 Android와 같은 필드를 가진 `Diagnostics` struct를 반환합니다(7절). 시각은 epoch millisecond(`Int64`)입니다. `DeliveryErrorKind`의 case 이름은 Swift 형식(`.network`, `.rateLimited`, ...)이고, raw value는 Android 이름(`"NETWORK"`, `"RATE_LIMITED"`, ...)입니다.

### iOS의 전송

전송은 6절을 따르되, 다음이 다릅니다(ADR-0047 D8, ticket 09 결과).

- WorkManager가 없고, 주기적 백그라운드 전송도 없습니다. SDK는 `BGTaskScheduler`를 쓰지 않습니다.
- SDK는 이벤트 20개가 저장되었을 때, 첫 이벤트 10초 뒤, 실행 시작 때, 앱이 foreground로 돌아올 때 전송합니다.
- 앱이 background로 들어가면 SDK는 `beginBackgroundTask` 안에서 전송을 한 번 시작합니다. 시스템이 task를 끝내면 요청은 취소되고 큐는 그대로 남습니다.
- 앱이 active인 동안 SDK는 저장된 다음 시도 시각에 timer를 하나 설정합니다. background에서는 timer가 돌지 않습니다.
- background에서 종료된 뒤 남은 이벤트는 다음 실행 때 전송됩니다.

### 파일과 데이터 보호

SDK는 설치 ID, 큐 database(`-wal`, `-shm`, `-journal` 파일 포함), opt-out marker를 `Application Support/signaldock/`에 둡니다. 이 디렉터리와 설치 ID 파일은 백업에서 제외됩니다(`isExcludedFromBackup`). Apple은 제외한 항목이 복원된 기기에 절대 나타나지 않는다고 보장하지 않으므로, 복원된 기기가 드물게 같은 설치 ID를 가질 수 있습니다(ADR-0047 D6과 2026-10-28 amendment). 앱을 삭제하면 파일도 삭제되므로 재설치하면 새 ID를 받고, 전송하지 못한 이벤트는 사라집니다.

SDK는 이 파일들에 Data Protection class `completeUntilFirstUserAuthentication`을 지정합니다(ADR-0047 issue 20 amendment). 재시작 뒤 첫 잠금 해제 전에는 시스템이 앱이 이 파일을 열게 하지 않습니다. 앱이 그보다 일찍 시작하면(예를 들어 background launch) `initialize(_:)`는 `.failure(.storageFailure(message:))`를 반환하고 instance는 없습니다. 이 구간에서 수집하려는 이벤트는 버려집니다. 첫 잠금 해제 뒤에 `initialize(_:)`를 다시 호출하세요. Gap: Simulator는 Data Protection을 적용하지 않으므로, 이 실패와 파일의 class는 실제 기기에서 확인하지 않았습니다(ADR-0047 ticket 07 결과).

### Privacy manifest

SDK는 `PrivacyInfo.xcprivacy`를 포함합니다(ADR-0047 D14). tracking 없음, tracking domain 없음. 수집 데이터 유형은 `DeviceID`(설치 ID)와 `ProductInteraction`(앱의 이벤트)이며, 둘 다 사용자와 연결되지 않고, tracking에 쓰이지 않으며, 목적은 Analytics입니다. SDK는 required-reason API를 쓰지 않습니다.

**이벤트 property는 앱의 데이터입니다.** SDK는 `property(_:_:)`에 무엇을 넣는지 알 수 없습니다. property가 Apple이 정한 다른 데이터 유형(예: 이메일 주소, 위치, 구매 내역)을 담으면, 그 유형을 앱 자신의 privacy manifest와 App Store 개인정보 답변에 선언하세요. SDK의 manifest는 위의 데이터 유형만 다룹니다.

### 로그

SDK는 `os.Logger`로 기록하며 subsystem은 `local.signaldock`, category는 `SignalDock`입니다(`sdk-ios/Sources/SignalDock/Internal/Init/Logging.swift`). Console.app이나 `log stream --predicate 'subsystem == "local.signaldock"'`로 보세요. 10절의 Logcat tag를 대신합니다. 메시지는 고정 문구의 닫힌 집합이라서 이벤트 내용, 이벤트 이름, 설치 ID, collection key, HTTP body, 시스템 오류 문구를 담는 메시지는 없습니다. `logLevel` `.debug`는 debug, warning, error 메시지를 쓰고, `.info`와 `.warn`은 warning과 error, `.error`는 error만 쓰며, `.none`은 아무것도 쓰지 않습니다. 기본값은 `.warn`입니다.

| 수준 | 메시지 |
| --- | --- |
| debug | `initialized`; `opt-out set`; `delivery attempt started`; `delivery attempt finished`; `delivery failed; see diagnostics for the kind` |
| warning | `opt-out state could not be stored`; `installation id file is invalid; generating a new id`; `track storage failed`; `track cancelled during storage; the event may already be stored`; `diagnostics storage failed`; `clearQueue storage failed`; `delivery paused by a configuration, authentication or contract error; rechecking every 30 minutes`; `delivery resumed after a successful ACK`; `delivery run failed in storage; the queue is unchanged`; `drop counter could not be written; the drop is not counted`; `client report failed in storage; the counters are unchanged`; `server contract version is deprecated; see diagnostics for the dates` |

WorkManager 메시지는 없습니다. deprecation 메시지에는 날짜가 없습니다. 날짜는 `diagnostics()`에서 읽으세요.

`InitError.storageFailure(message:)`와 `TrackResult.storageError(message:)`는 `StorageError(<kind>, <code>)` 또는 다른 오류의 타입 이름을 담습니다. 코드만 담으며, Android처럼 예외 클래스 이름이 아닙니다(ADR-0047 ticket 08 결과).

### 알려진 한계

- 테스트에 쓸 iOS 15 runtime이 없었으므로, SDK는 iOS 15용으로 빌드되지만 테스트는 더 새로운 Simulator runtime에서만 실행했습니다(ADR-0047 "iOS 15 Simulator fallback").
- 실제 기기에서는 확인하지 않았고, 백업 복원과 디스크 가득 참도 확인하지 않았습니다(ADR-0047 "Known limits").
- source ZIP에는 코드 서명이 없습니다. Apple이 서명 확인을 명시한 대상은 binary 의존성뿐입니다(ADR-0047 "Known limits").
- 0.1.0의 범위 밖: remote SwiftPM, CocoaPods, XCFramework, app extension, Objective-C와 completion-handler API, `BGTaskScheduler`, Flutter, React Native, Unity, WebView bridge(ADR-0047 Context).
