# 오픈 채팅방 이벤트 전파 계약

## 1. 목적

이 문서는 서버에서 클라이언트로 전파하는 오픈 채팅방 이벤트 계약을 정의한다.

본 문서는 클라이언트가 실제로 수신하는 이벤트의 분류, payload, 적용 기준을 정의한다.

---

## 2. 이벤트 분류

서버에서 클라이언트로 전달되는 이벤트는 전달 보장 수준에 따라 구분한다.

### 2.1 Outbox WebSocket event

Outbox WebSocket event는 DB에 확정된 상태 변경을 클라이언트에게 전파하기 위한 이벤트다.

전파 유실 시 사용자 화면 정합성이 깨질 수 있으므로 `outbox_event`에 기록하고 relay worker가 WebSocket으로 broadcast한다.

기본 적용 대상:

```text
MESSAGE_SENT
MESSAGE_EDITED
MESSAGE_DELETED_BY_USER
ROOM_CLOSED
ROOM_JOINED
ROOM_LEFT
ROOM_OWNER_TRANSFERRED
ROOM_SYSTEM_MANAGED
```

Outbox WebSocket event는 at-least-once 전달을 전제로 한다.

클라이언트는 같은 이벤트가 두 번 이상 도착해도 화면에 중복 반영하지 않아야 한다.

### 2.2 Direct WebSocket event

Direct WebSocket event는 현재 연결 중인 클라이언트에게 실시간 표시를 보정하기 위한 이벤트다.

DB 상태 변경을 durable하게 전달하기 위한 이벤트가 아니므로 OutboxEvent로 적재하지 않는다.

기본 적용 대상:

```text
READ_CURSOR_UPDATED
```

Direct WebSocket event는 best-effort 전달을 전제로 한다.

유실되어도 조회 응답, reconnect catch-up, foreground 복귀 시점의 재조회로 복구할 수 있어야 한다.

---

## 3. 공통 envelope

서버가 클라이언트에게 전달하는 이벤트는 다음 공통 envelope를 가진다.

```text
ServerEvent:
  eventId
  eventType
  roomId
  deliveryType
  deliveryScope
  occurredAt
  payload
```

### 3.1 필드

```text
eventId:
  이벤트 중복 제거를 위한 식별자

eventType:
  MESSAGE_SENT
  MESSAGE_EDITED
  MESSAGE_DELETED_BY_USER
  ROOM_CLOSED
  ROOM_JOINED
  ROOM_LEFT
  ROOM_OWNER_TRANSFERRED
  ROOM_SYSTEM_MANAGED
  READ_CURSOR_UPDATED

roomId:
  이벤트가 속한 room

deliveryType:
  OUTBOX
  DIRECT

deliveryScope:
  ROOM_SUBSCRIBERS
  USER_CONNECTIONS
  ACTOR_CONNECTION
  OPERATOR_CONNECTIONS

occurredAt:
  서버에서 이벤트가 발생한 시각

payload:
  eventType별 payload
```

`eventId`는 Outbox WebSocket event에서 필수다.

Direct WebSocket event에서도 중복 제거와 디버깅을 위해 `eventId`를 포함할 수 있다.

---

## 4. 전파 범위

전파 범위는 이벤트를 어떤 WebSocket connection 집합에 보낼지 정의한다.

전파 범위는 WebSocket endpoint나 frame 형식을 정의하지 않는다.

본 모듈은 room마다 별도 WebSocket connection을 여는 방식을 전제로 하지 않는다.

하나의 WebSocket connection은 여러 room을 subscribe할 수 있다.

### 4.1 ROOM_SUBSCRIBERS

`ROOM_SUBSCRIBERS`는 `roomId`를 구독 중인 connection 집합이다.

room에 참여한 사용자가 모두 수신 대상이라는 뜻은 아니다.

현재 WebSocket connection에서 해당 room을 subscribe한 경우에만 수신 대상이 된다.

### 4.2 USER_CONNECTIONS

`USER_CONNECTIONS`는 특정 `userId`로 인증된 활성 connection 집합이다.

같은 사용자가 여러 기기 또는 여러 브라우저 탭으로 접속한 경우 모두 대상이 될 수 있다.

### 4.3 ACTOR_CONNECTION

`ACTOR_CONNECTION`은 현재 command를 요청한 connection이다.

command 응답으로 충분한 경우 별도 event를 보내지 않을 수 있다.

### 4.4 OPERATOR_CONNECTIONS

`OPERATOR_CONNECTIONS`는 운영자 권한을 가진 활성 connection 집합이다.

운영 알림이나 운영자 화면 갱신이 필요한 경우 사용한다.

---

## 5. 메시지 이벤트

메시지 생성, 수정, 삭제 이벤트는 Outbox WebSocket event다.

메시지 이벤트의 기본 전파 범위는 `ROOM_SUBSCRIBERS`다.

### 5.1 MESSAGE_SENT

`MESSAGE_SENT`는 room에 새 메시지가 생성되었음을 전파한다.

```text
deliveryType:
  OUTBOX

deliveryScope:
  ROOM_SUBSCRIBERS
```

```text
MESSAGE_SENT payload:
  messageId
  roomId
  roomSequence
  senderUserId
  clientMessageId
  type
  content
  visibility
  editVersion
  sentAt
```

클라이언트 적용 기준:

```text
- messageId 기준으로 기존 메시지와 병합한다.
- sender의 pending 메시지가 있으면 clientMessageId 기준으로 서버 확정 메시지와 병합한다.
- roomSequence 기준으로 room 안의 메시지 순서를 정렬한다.
- 같은 eventId 또는 같은 messageId의 MESSAGE_SENT가 중복 도착하면 중복 표시하지 않는다.
```

### 5.2 MESSAGE_EDITED

`MESSAGE_EDITED`는 기존 메시지의 content가 수정되었음을 전파한다.

```text
deliveryType:
  OUTBOX

deliveryScope:
  ROOM_SUBSCRIBERS
```

```text
MESSAGE_EDITED payload:
  messageId
  roomId
  roomSequence
  content
  editVersion
  editedAt
```

클라이언트 적용 기준:

```text
- messageId 기준으로 대상 메시지를 찾는다.
- 수신한 editVersion이 현재 화면의 editVersion보다 작거나 같으면 오래된 이벤트로 보고 무시할 수 있다.
- 수신한 editVersion이 더 크면 content, editVersion, editedAt을 갱신한다.
- 대상 메시지가 없으면 roomSequence 기준 catch-up 또는 메시지 재조회를 수행할 수 있다.
```

### 5.3 MESSAGE_DELETED_BY_USER

`MESSAGE_DELETED_BY_USER`는 기존 메시지가 사용자 삭제 표시 상태로 전환되었음을 전파한다.

```text
deliveryType:
  OUTBOX

deliveryScope:
  ROOM_SUBSCRIBERS
```

```text
MESSAGE_DELETED_BY_USER payload:
  messageId
  roomId
  roomSequence
  visibility
  editVersion
  deletedAt
```

`MESSAGE_DELETED_BY_USER` payload에는 일반 메시지 content를 포함하지 않는다.

클라이언트 적용 기준:

```text
- messageId 기준으로 대상 메시지를 찾는다.
- 수신한 editVersion이 현재 화면의 editVersion보다 작거나 같으면 오래된 이벤트로 보고 무시할 수 있다.
- 수신한 editVersion이 더 크면 메시지를 삭제 표시 상태로 갱신한다.
- 삭제된 메시지를 placeholder로 표시할지 목록에서 제외할지는 UI 정책에서 정한다.
```

---

## 6. 방과 참여 이벤트

방과 참여 이벤트는 Outbox WebSocket event다.

방과 참여 이벤트는 DB에 확정된 room, membership, owner, operationStatus 변경을 구독자에게 전파한다.

### 6.1 ROOM_CLOSED

`ROOM_CLOSED`는 room이 종료되었음을 전파한다.

```text
deliveryType:
  OUTBOX

deliveryScope:
  ROOM_SUBSCRIBERS
```

```text
ROOM_CLOSED payload:
  roomId
  closedAt
  lastMessageSequence
```

클라이언트 적용 기준:

```text
- room을 종료 상태로 표시한다.
- 채팅 화면에서 메시지 전송, 수정, 삭제, 구독 유지 동작을 중단한다.
- 이미 열려 있는 stale한 채팅 화면은 더 이상 사용 가능 상태로 두지 않는다.
```

### 6.2 ROOM_JOINED

`ROOM_JOINED`는 사용자가 room에 참여했음을 전파한다.

```text
deliveryType:
  OUTBOX

deliveryScope:
  ROOM_SUBSCRIBERS
```

```text
ROOM_JOINED payload:
  roomId
  userId
  joinedSequence
  joinedAt
```

`ROOM_JOINED`는 참여자 수, 참여자 목록, room 상세 화면을 보정하는 데 사용할 수 있다.

### 6.3 ROOM_LEFT

`ROOM_LEFT`는 사용자가 room에서 나갔음을 전파한다.

```text
deliveryType:
  OUTBOX

deliveryScope:
  ROOM_SUBSCRIBERS
```

```text
ROOM_LEFT payload:
  roomId
  userId
  leftSequence
  leftAt
```

`ROOM_LEFT`는 참여자 수, 참여자 목록, room 상세 화면을 보정하는 데 사용할 수 있다.

### 6.4 ROOM_OWNER_TRANSFERRED

`ROOM_OWNER_TRANSFERRED`는 room owner가 변경되었음을 전파한다.

```text
deliveryType:
  OUTBOX

deliveryScope:
  ROOM_SUBSCRIBERS
```

```text
ROOM_OWNER_TRANSFERRED payload:
  roomId
  previousOwnerUserId
  newOwnerUserId
  transferredAt
```

클라이언트는 room 상세 또는 관리 화면의 owner 표시를 갱신한다.

### 6.5 ROOM_SYSTEM_MANAGED

`ROOM_SYSTEM_MANAGED`는 owner 계정 비활성화 등으로 room이 운영 개입 상태가 되었음을 전파한다.

```text
deliveryType:
  OUTBOX

deliveryScope:
  ROOM_SUBSCRIBERS
  OPERATOR_CONNECTIONS
```

```text
ROOM_SYSTEM_MANAGED payload:
  roomId
  operationStatus
  reason
  changedAt
```

`SYSTEM_MANAGED`는 채팅 서비스 사용 불가 상태를 의미하지 않는다.

클라이언트는 사용자 행동 제한 여부를 이벤트만으로 최종 판단하지 않고, 이후 command 또는 query 결과를 따른다.

---

## 7. 읽음 이벤트

읽음 이벤트는 Direct WebSocket event다.

읽음 이벤트는 실시간 표시 보정을 위한 이벤트이며, 영속 전달 보장 대상이 아니다.

읽음 이벤트가 유실되어도 서버 조회 결과로 복구할 수 있어야 한다.

### 7.1 READ_CURSOR_UPDATED

`READ_CURSOR_UPDATED`는 특정 사용자의 read cursor가 갱신되었음을 전파한다.

기본 수신 대상은 해당 사용자의 다른 connection이다.

```text
deliveryType:
  DIRECT

deliveryScope:
  USER_CONNECTIONS
```

```text
READ_CURSOR_UPDATED payload:
  roomId
  userId
  previousReadSequence
  lastReadSequence
  readAt
```

`previousReadSequence`는 갱신 전 `ChatReadCursor.lastReadSequence`다.

`lastReadSequence`는 갱신 후 `ChatReadCursor.lastReadSequence`다.

클라이언트 적용 기준:

```text
- 본인 userId의 read cursor를 갱신한다.
- lastReadSequence가 현재 화면의 read cursor보다 작으면 무시한다.
- unread count는 갱신된 read cursor를 기준으로 재계산하거나 서버 조회 결과를 따른다.
- 현재 표시 중인 메시지 중 previousReadSequence < message.roomSequence <= lastReadSequence 구간에 포함되고 sender가 userId가 아닌 메시지는 unreadReceiptCount를 임시 보정할 수 있다.
- 이벤트 중복, 유실, reconnect 이후 최종 read receipt count는 getReadReceiptCounts 조회 결과로 보정한다.
```

---

## 8. 클라이언트 공통 적용 기준

클라이언트는 이벤트를 다음 기준으로 적용한다.

```text
중복 제거:
  eventId

메시지 병합:
  messageId
  clientMessageId

메시지 정렬:
  roomSequence

메시지 수정/삭제 충돌 방지:
  editVersion

누락 복구:
  lastHandledSequence
  afterSequence catch-up
```

### 8.1 중복 이벤트

Outbox WebSocket event는 at-least-once 전달되므로 같은 이벤트가 중복 도착할 수 있다.

클라이언트는 `eventId`를 기준으로 같은 이벤트를 중복 적용하지 않는다.

같은 메시지가 서로 다른 경로로 도착한 경우 `messageId` 또는 `clientMessageId` 기준으로 하나의 메시지 상태로 병합한다.

### 8.2 오래된 이벤트

수정 또는 삭제 이벤트가 현재 화면 상태보다 오래된 경우 클라이언트는 이벤트를 무시할 수 있다.

메시지 수정/삭제 이벤트의 최신성은 `editVersion`으로 판단한다.

### 8.3 순서와 누락

메시지 이벤트는 `roomSequence` 기준으로 정렬한다.

클라이언트가 처리한 마지막 `roomSequence` 이후 누락이 의심되면 `afterSequence` 기준 catch-up으로 복구한다.

실시간 이벤트만으로 메시지 목록의 완전성을 판단하지 않는다.

### 8.4 direct 이벤트 유실

Direct WebSocket event는 유실될 수 있다.

읽음 표시, read cursor, unread count, unread receipt count의 최종 값은 서버 조회 결과로 보정한다.
