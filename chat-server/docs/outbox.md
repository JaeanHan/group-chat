# Outbox Relay 설계

## 1. 목적

본 문서는 공용 오픈 채팅방 모듈에서 Outbox Pattern을 사용하는 기준을 정의한다.

Outbox는 메시지 저장과 실시간 전달 사이의 장애를 흡수하기 위한 전달 보장 장치다.

Outbox는 message broker가 아니라 DB 기반 durable relay queue다.

WebSocket은 client delivery channel이다.

Outbox는 DB에 확정된 상태 변경을 WebSocket broadcast 경로로 전달하기 위한 작업 목록을 보존한다.

```text
sendMessage transaction
  - chat_message 저장
  - outbox_event 저장
  - commit

outbox relay
  - outbox_event 조회
  - WebSocket broadcast
  - 처리 상태 기록
```

Outbox는 영구 감사 로그가 아니다.

감사, 분석, 장기 보관이 필요한 이벤트는 별도 AuditLog, 로그 파일, 장기 보관 스토리지에서 다룬다.

---

## 2. 보장 수준

Outbox relay의 전달 보장 수준은 `at-least-once`다.

```text
의미:
  - 이벤트는 최소 한 번 전달되도록 시도한다.
  - 장애 상황에서는 같은 이벤트가 두 번 이상 전달될 수 있다.
```

`exactly-once` 전달은 기본 목표로 두지 않는다.

DB transaction과 WebSocket broadcast는 하나의 원자적 transaction으로 묶을 수 없기 때문이다.

따라서 본 모듈은 다음 원칙을 사용한다.

```text
전달:
  at-least-once

처리:
  idempotent

사용자 경험:
  roomSequence 기준으로 한 번만 보이도록 처리
```

수신자는 `eventId`, `messageId`, `roomSequence` 중 하나 이상의 기준으로 중복 이벤트를 무시할 수 있어야 한다.

---

## 3. 메시지 저장 성공과 실시간 전달 실패의 구분

`sendMessage` transaction이 실패한 경우 메시지는 저장되지 않은 것이다.

이 경우 클라이언트는 메시지 전송 실패를 표시하고 사용자가 재전송할 수 있다.

```text
sendMessage transaction 실패
  -> message 없음
  -> outbox_event 없음
  -> 클라이언트 전송 실패 표시 가능
```

반대로 `sendMessage` transaction이 성공한 경우 메시지는 저장된 것이다.

```text
sendMessage transaction 성공
  -> chat_message 저장됨
  -> roomSequence 부여됨
  -> outbox_event 저장됨
```

이후 Outbox publish가 실패하는 것은 메시지 전송 실패가 아니라 실시간 동기화 지연으로 본다.

```text
Outbox publish 실패
  -> message는 이미 저장됨
  -> sender에게 같은 메시지를 다시 보내도록 요구하지 않음
  -> relay retry 또는 client catch-up으로 복구
```

sender의 pending 말풍선은 `sendMessage` 응답에 포함된 `messageId`, `roomSequence`, `clientMessageId`를 기준으로 확정 메시지로 전환한다.

---

## 4. OutboxEvent 생성 원칙

도메인 상태 변경 중 외부 전달이 필요한 변경은 같은 DB transaction 안에서 `outbox_event`로 기록한다.

메시지 전송이 성공하는 경우 서버는 같은 DB transaction 안에서 다음 두 데이터를 함께 저장한다.

```text
1. chat_message row
2. outbox_event row
```

`outbox_event`는 WebSocket broadcast가 필요하다는 사실을 기록하는 데이터다.

실제 WebSocket broadcast는 DB transaction commit 이후 relay worker가 수행한다.

명확히 잘못된 event payload는 `outbox_event`로 저장하지 않는다.

```text
저장 전 검증:
  - eventType 지원 여부
  - 필수 payload 필드 존재 여부
  - payload 직렬화 가능 여부
  - payload 크기 제한
```

worker는 publish 전에도 방어적으로 payload를 검증할 수 있다.

검증 실패 이벤트는 재시도해도 성공할 가능성이 낮으므로 영구 실패로 격리한다.

---

## 5. Outbox 적용 대상

OutboxEvent는 DB에 확정된 상태 변경이 다른 client에게 전파되어야 하고, 전파 유실 시 사용자 화면 정합성이 깨지는 이벤트에 사용한다.

기본 적용 대상은 다음과 같다.

```text
- MESSAGE_SENT
- MESSAGE_EDITED
- MESSAGE_DELETED_BY_USER
- ROOM_CLOSED
```

선택 적용 대상은 다음과 같다.

```text
- ROOM_JOINED
- ROOM_LEFT
- ROOM_OWNER_TRANSFERRED
- ROOM_SYSTEM_MANAGED
```

DB 상태 변경과 무관하거나 유실되어도 정합성이 깨지지 않는 휘발성 이벤트는 OutboxEvent로 적재하지 않는다.

```text
- typing indicator
- online presence
- transient cursor/focus state
```

휘발성 이벤트는 필요하면 direct WebSocket으로 전달할 수 있다.

---

## 6. OutboxEvent 상태

OutboxEvent는 다음 상태를 가진다.

```text
PENDING
PROCESSING
RETRY_WAIT
PUBLISHED
FAILED
```

| 상태 | 의미 |
|---|---|
| `PENDING` | 아직 publish를 시도하지 않은 상태 |
| `PROCESSING` | relay worker가 claim하여 처리 중인 상태 |
| `RETRY_WAIT` | 일시 실패 후 다음 재시도 시각까지 대기 중인 상태 |
| `PUBLISHED` | WebSocket broadcast 경로로 전달 완료한 상태 |
| `FAILED` | 자동 publish loop에서 격리된 상태 |

`FAILED`는 이벤트를 삭제하거나 유실했다는 의미가 아니다.

`FAILED`는 운영 확인 또는 repair job이 필요한 격리 상태다.

`FAILED`는 단일 상태로 관리하되, 실패 원인은 `failureReason`으로 구분한다.

```text
PAYLOAD_INVALID
PUBLISH_RETRY_EXHAUSTED
STALE_PENDING_EXPIRED
STALE_PROCESSING_EXPIRED
STALE_RETRY_EXPIRED
LEASE_RENEW_FAILED
UNKNOWN
```

---

## 7. Relay Worker Pool

Outbox relay는 고정된 worker pool로 운영한다.

worker 개수는 고정 상수가 아니라 운영 지표를 기준으로 조정한다.

초기 worker 수는 작게 시작하고, publish lag와 backlog를 보며 늘린다.

```text
초기 예시:
  4~8 relay workers
```

초기 구현에서 relay worker는 Spring Boot application 내부 background worker로 운영할 수 있다.

처리량 증가 또는 장애 격리가 필요해지면 relay worker를 별도 Spring Boot process로 분리할 수 있다.

동시에 처리 가능한 room 수는 worker 수에 의해 제한될 수 있다.

worker 수보다 활성 room 수가 많으면 일부 room의 이벤트는 `PENDING` 또는 `RETRY_WAIT` 상태로 대기할 수 있다.

이는 정상적인 backpressure 동작이다.

시스템은 다음 지표를 기준으로 worker pool 크기 조정이 필요한지 판단한다.

```text
- pending_event_count
- pending_room_count
- oldest_pending_age
- publish_lag_ms
- events_published_per_second
- retry_count
- failed_count
- worker_busy_ratio
- room_lease_wait_time
```

---

## 8. Room-level Lease

현재 구현은 별도 message broker 없이 WebSocket 서버가 직접 client connection에 broadcast한다.

따라서 같은 room 이벤트의 publish 순서를 서버가 최대한 보장해야 한다.

Outbox relay는 `room-level lease` 기반 worker pool을 사용한다.

worker는 특정 room에 영구 배정되지 않는다.

worker는 짧은 시간 동안 특정 room의 publish 권한을 lease로 획득한다.

```text
room lease
  - roomId
  - ownerWorkerId
  - leaseToken
  - leaseUntil
```

같은 room의 lease는 동시에 하나의 worker만 가질 수 있다.

worker는 해당 room의 lease를 획득한 뒤에만 그 room의 OutboxEvent를 claim/publish할 수 있다.

```text
1. worker가 publish할 PENDING event가 있는 room 후보를 찾는다.
2. 해당 room의 lease 획득을 시도한다.
3. lease 획득에 성공한 worker만 해당 room 이벤트를 처리한다.
4. 같은 room 이벤트는 roomSequence ASC 순서로 publish한다.
5. 처리 후 lease를 release하거나 필요 시 renew한다.
```

room lease는 정상 처리 시 worker가 명시적으로 release한다.

worker 장애로 release가 수행되지 않을 수 있으므로 `leaseUntil`을 둔다.

worker는 `leaseToken`이 유효한 동안에만 해당 room 이벤트를 publish할 수 있다.

처리 시간이 `leaseDuration`에 가까워지면 worker는 lease를 renew해야 한다.

lease renew에 실패한 worker는 해당 room publish를 중단해야 한다.

한 worker가 특정 room을 장시간 독점하지 않도록 다음 제한을 둘 수 있다.

```text
- maxEventsPerLease
- maxTimePerLease
```

room backlog가 남아 있어도 worker는 lease를 양보할 수 있어야 한다.

---

## 9. Event Claim

worker는 조회한 OutboxEvent를 바로 publish하지 않는다.

반드시 claim 상태 변경에 성공한 이벤트만 publish한다.

claim 시 worker는 매번 새로운 `lockToken`을 생성한다.

```text
claim 결과:
  status = PROCESSING
  lockedBy = workerId
  lockToken = generated UUID
  lockedUntil = now + lockDuration
```

`lockedBy`는 어떤 worker가 처리 중인지 확인하기 위한 운영 정보다.

`lockToken`은 이번 claim 시도 자체를 식별하는 토큰이다.

publish 성공 또는 실패 후 상태를 변경할 때는 `lockToken`이 일치하는 경우에만 업데이트할 수 있다.

`lockedUntil`이 지난 `PROCESSING` 이벤트는 다른 worker가 다시 claim할 수 있다.

claim 가능한 이벤트는 다음과 같다.

```text
- status = PENDING
- status = RETRY_WAIT AND nextRetryAt <= now
- status = PROCESSING AND lockedUntil < now
```

worker는 room lease를 보유한 room의 이벤트만 claim할 수 있다.

---

## 10. Claim, Publish, Complete 흐름

Outbox relay는 외부 I/O를 DB transaction 안에서 수행하지 않는다.

publish는 DB transaction 밖에서 수행한다.

기본 처리 흐름은 다음과 같다.

```text
Transaction A: claim
  - room lease 확인
  - claim 가능한 event를 PROCESSING으로 변경
  - lockedBy, lockToken, lockedUntil 기록
  - commit

Outside transaction: publish
  - WebSocket broadcast 수행

Transaction B: complete
  - lockToken이 일치하는 event만 상태 변경
  - 성공 event는 PUBLISHED
  - 실패 event는 RETRY_WAIT 또는 FAILED
```

publish를 DB transaction 안에서 수행하지 않는 이유는 WebSocket broadcast 같은 외부 I/O가 DB lock을 오래 점유할 수 있기 때문이다.

내부 queue에 적재된 것만으로 OutboxEvent를 `PUBLISHED` 처리하지 않는다.

`PUBLISHED`는 실제 WebSocket broadcast 수행 이후에만 기록한다.

---

## 11. Batch 처리

relay worker는 batch 단위로 claim할 수 있다.

단, publish 결과는 event 단위로 판단한다.

```text
batch claim
  -> event별 publish
  -> 성공 eventId 묶어서 PUBLISHED update
  -> 실패 eventId 묶어서 RETRY_WAIT 또는 FAILED update
```

상태 업데이트는 성공/실패 event를 묶어 batch로 처리할 수 있다.

단, 각 event의 최종 상태는 개별 publish 결과와 일치해야 한다.

같은 room 이벤트는 `roomSequence ASC` 순서로 publish한다.

---

## 12. Publish Ordering

Outbox relay는 여러 worker로 병렬 처리할 수 있다.

단, 병렬 처리 단위는 event가 아니라 room이다.

같은 room에 속한 이벤트는 roomSequence 순서를 보존하기 위해 동시에 둘 이상의 worker가 publish하지 않는다.

서로 다른 room에 속한 이벤트는 서로 다른 worker가 병렬로 publish할 수 있다.

```text
같은 room:
  roomSequence ASC 순서로 직렬 publish

서로 다른 room:
  병렬 publish 가능
```

클라이언트는 최종적으로 `roomSequence` 기준으로 메시지를 정렬하고 중복 이벤트를 제거해야 한다.

향후 Kafka 등 partition 기반 broker를 도입하는 경우, 같은 room 이벤트가 같은 partition으로 전달되도록 `roomId`를 partition key로 사용하는 것을 권장한다.

현재 구현에서 broker partition key는 구현 범위에 포함하지 않는다.

---

## 13. Retry 정책

채팅 도메인 이벤트는 발송 최우선 이벤트로 본다.

단, 자동 publish loop가 영구적으로 동일 실패 이벤트에 묶이지 않도록 반복 실패 이벤트는 격리할 수 있어야 한다.

실패는 크게 두 종류로 구분한다.

```text
일시 실패:
  - WebSocket 서버 일시 장애
  - 네트워크 timeout
  - relay worker 재시작
  - connection pool 일시 포화
  - publish 경로의 일시 backpressure

영구 실패 가능성이 높은 실패:
  - unsupported eventType
  - invalid payload
  - payload serialization error
  - payload size 초과
```

일시 실패는 빠르게 재시도한다.

재시도 간격은 점진적으로 늘리되 최대 대기 시간을 둔다.

```text
retry backoff:
  initial fast retry
  -> capped backoff
```

영구 실패 가능성이 높은 이벤트는 자동 재시도하지 않고 `FAILED`로 격리한다.

일시 실패도 일정 횟수 또는 일정 시간 이상 반복되면 `FAILED`로 격리하고 운영 확인 대상으로 전환할 수 있다.

`FAILED` 이벤트는 archive 및 monitoring system을 통해 확인한다.

원인 수정 후 재처리가 필요한 경우 repair job은 archive된 이벤트 또는 별도 복구 입력을 기준으로 새 OutboxEvent를 생성할 수 있다.

---

## 14. Duplicate Publish

Outbox relay는 같은 이벤트를 두 번 이상 publish할 수 있다.

대표적인 상황은 다음과 같다.

```text
publish 성공
  -> PUBLISHED update 전 worker 장애
  -> lockedUntil 만료
  -> 다른 worker가 다시 claim
  -> duplicate publish
```

이는 `at-least-once` 구조에서 허용된 동작이다.

수신자는 다음 기준으로 중복 이벤트를 방어해야 한다.

```text
- eventId
- messageId
- roomSequence
- clientMessageId
```

채팅 메시지는 `roomSequence`와 `messageId`를 최종 중복 방어 기준으로 사용할 수 있다.

---

## 15. Catch-up

Outbox publish 실패는 메시지 저장 실패가 아니라 실시간 전달 지연으로 본다.

클라이언트는 WebSocket 재연결, 앱 foreground 복귀, room 진입 시 마지막으로 처리한 `roomSequence` 이후 메시지를 조회하여 누락 메시지를 복구할 수 있어야 한다.

```text
afterSequence = lastReceivedSequence
```

예시는 다음과 같다.

```text
GET /rooms/{roomId}/messages?afterSequence={lastReceivedSequence}
```

서버는 `roomSequence > afterSequence` 조건으로 메시지를 반환한다.

클라이언트는 catch-up 응답과 WebSocket 이벤트를 `roomSequence` 기준으로 병합한다.

---

## 16. Cleanup

OutboxEvent cleanup은 상태별로 다르게 적용한다.

운영자는 `outbox_event` table을 직접 조회하지 않는 것을 전제로 한다.

운영 확인이 필요한 이벤트는 DB에 장기 보관하지 않고 archive 및 monitoring system을 통해 확인한다.

```text
PUBLISHED:
  - 보관 기간 이후 삭제 가능

FAILED:
  - archive 성공 후 삭제 가능

PENDING:
  - 삭제하지 않음
  - 보관 한계를 넘으면 FAILED로 전환 후 archive

PROCESSING:
  - 삭제하지 않음
  - lockedUntil 만료 시 재claim 대상
  - 보관 한계를 넘으면 FAILED로 전환 후 archive

RETRY_WAIT:
  - 삭제하지 않음
  - 보관 한계를 넘으면 FAILED로 전환 후 archive
```

초기 운영 기준은 다음으로 둔다.

```text
PUBLISHED:
  2일 후 삭제

FAILED:
  archive 성공 후 삭제 가능

PENDING:
  5분 이상 지속 시 alert
  2일 이상 지속 시 FAILED 전환 후 archive

PROCESSING:
  2일 이상 지속 시 FAILED 전환 후 archive

RETRY_WAIT:
  2일 이상 지속 시 FAILED 전환 후 archive
```

`PENDING`, `PROCESSING`, `RETRY_WAIT` 상태가 보관 한계를 넘은 경우 바로 삭제하지 않고 `FAILED`로 전환한다.

이때 원래 상태에 따라 `failureReason`을 기록한다.

```text
PENDING -> FAILED / STALE_PENDING_EXPIRED
PROCESSING -> FAILED / STALE_PROCESSING_EXPIRED
RETRY_WAIT -> FAILED / STALE_RETRY_EXPIRED
```

`FAILED` event archive는 JSON Lines 형식을 사용할 수 있다.

archive에는 최소한 다음 정보를 포함한다.

```text
eventId
eventType
roomId
roomSequence
status
failureReason
attemptCount
lastError
payload
createdAt
failedAt
archivedAt
```

cleanup은 작은 batch 단위로 반복 수행하여 DB 부하를 제한한다.

`createdAt` 기준 partition을 사용하는 경우 cleanup은 보관 기간이 지난 partition drop을 우선할 수 있다.

단, 해당 partition에 `PENDING`, `PROCESSING`, `RETRY_WAIT` 상태 이벤트가 남아 있으면 drop하지 않는다.

`FAILED` event는 archive 완료 후 partition drop 또는 batch delete 대상이 될 수 있다.

`FAILED` event는 relay worker의 publish 대상이 아니다.

worker 조회는 status 기반 index를 사용하므로 `FAILED` event는 hot path에서 제외되어야 한다.

단, `FAILED` event가 장기간 누적되면 테이블과 인덱스 크기가 증가하므로 archive 완료 후 빠르게 cleanup한다.

---

## 17. Partition and Index

MariaDB를 기준으로 `outbox_event`는 `createdAt` 날짜 기준 `RANGE` partition을 사용할 수 있다.

partition의 주 목적은 `PUBLISHED` event cleanup이다.

relay worker 조회 성능은 partition만으로 보장하지 않는다.

worker 조회 성능을 위해 다음 조회 패턴을 기준으로 index를 설계한다.

```text
room 후보 찾기:
  status, nextRetryAt, createdAt

room lease 후 event claim:
  roomId, status, nextRetryAt, roomSequence

processing timeout 회수:
  status, lockedUntil

cleanup 대상 조회:
  status, publishedAt
```

partition pruning은 `createdAt` 조건이 있는 조회에서만 기대할 수 있다.

따라서 partition은 cleanup을 위한 장치이고, index는 worker 조회를 위한 장치다.

둘은 대체 관계가 아니라 함께 사용할 수 있는 최적화다.

`roomId` 기준 partition은 기본 설계에 포함하지 않는다.

room-level lease 방식에서는 조회가 두 단계로 나뉜다.

lease 획득 전에는 전체 room 중 처리 가능한 pending event가 있는 room 후보를 찾아야 한다.

```text
status IN (PENDING, RETRY_WAIT)
AND nextRetryAt <= now
ORDER BY createdAt
```

lease 획득 후에는 특정 room의 event를 조회한다.

```text
roomId = ?
AND status IN (PENDING, RETRY_WAIT)
AND nextRetryAt <= now
ORDER BY roomSequence
```

`roomId` partition은 lease 획득 후 특정 room 조회에는 유리해 보일 수 있다.

하지만 lease 획득 전 room 후보 탐색을 여러 partition 조회 문제로 만들 수 있으므로 기본 설계에 포함하지 않는다.

`roomId`는 partition key가 아니라 lease 획득 후 이벤트 조회를 위한 index 구성에서 고려한다.

---

## 18. eventId

`eventId`는 서버가 생성한 UUID를 사용한다.

`eventId`는 이벤트 중복 처리와 운영 추적을 위한 식별자다.

UUID 충돌 가능성은 실무적으로 무시 가능한 수준으로 본다.

수신자는 `eventId`만이 아니라 `messageId` 또는 `roomSequence`도 함께 사용하여 중복 이벤트를 방어할 수 있다.

MariaDB partition 제약으로 인해 `createdAt` 기준 partition table에서는 `eventId` 단독 unique 제약을 둘 수 없을 수 있다.

본 모듈은 UUID 생성 전략을 신뢰하며, 별도 eventId registry는 기본 설계에 포함하지 않는다.

strict global uniqueness가 필요한 환경에서는 별도 registry table을 둘 수 있다.

---

## 19. Monitoring

Outbox relay는 다음 상태를 모니터링해야 한다.

```text
- pending_event_count
- pending_room_count
- oldest_pending_age
- publish_lag_ms
- published_events_per_second
- retry_count
- failed_count
- processing_timeout_count
- duplicate_publish_count
- worker_busy_ratio
- room_lease_wait_time
- room_lease_renew_failure_count
```

다음 상황은 운영 알림 대상이다.

```text
- PENDING event가 기준 시간 이상 지속
- FAILED event 발생
- publish lag 급증
- retry_count 급증
- room lease renew 실패 반복
- worker가 장시간 publish를 수행하지 못함
```

---

## 20. 확장 경로

초기 구현은 MariaDB 기반 Outbox relay와 WebSocket direct broadcast를 사용한다.

처리량 요구가 증가하면 다음 확장 방안을 검토할 수 있다.

```text
- relay worker 수 증가
- room lease scheduling 고도화
- createdAt 기준 partition 운영
- WebSocket 서버 분리
- Kafka, Redis Streams, NATS JetStream 등 broker 도입
```

broker를 도입하는 경우에도 `roomSequence`는 최종 메시지 순서 기준으로 유지한다.
