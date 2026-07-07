# 공용 오픈 채팅방 모듈 상태전이도

## 1. 범위

본 문서는 공용 오픈 채팅방 모듈의 기본 상태전이 규칙을 정의한다.

현재 범위는 다음 기능으로 제한한다.

```text
- 오픈 채팅방 생성
- 오픈 채팅방 목록 조회
- 오픈 채팅방 상세 조회
- 오픈 채팅방 참여
- 오픈 채팅방 메시지 송수신
- 메시지 수정
- 메시지 삭제
- 오픈 채팅방 나가기
- 오픈 채팅방 종료
- WebSocket 연결 및 room 구독
```

`CLOSED` 상태의 대화방은 일반 USER API에서 조회하지 않는다.

---

## 2. 공통 인증/인가 원칙

모든 요청은 JWT access token 검증을 선행한다.

Resource Server는 JWT의 signature, issuer, audience, expiration을 검증한 뒤 `sub` claim을 `userId`로 해석한다.

```text
AuthenticatedUser.userId = jwt.sub
```

공용 오픈 채팅방 모듈은 JWT의 `roles`만으로 room 권한을 판단하지 않는다.

```text
JWT roles
  -> 전역 인증 정보

jwt.sub
  -> userId

공용 채팅방 DB
  -> 해당 userId가 특정 room에서 어떤 관계인지 판단
```

예를 들어 room owner 여부는 JWT role이 아니라 room 도메인 데이터로 판단한다.

```text
room.ownerUserId == authenticatedUser.userId
```

즉, `ROOM_OWNER`는 JWT role이 아니라 특정 room에 대한 도메인 관계다.

---

## 3. 상태전이 대상

공용 오픈 채팅방 모듈의 상태전이 대상은 다음과 같이 분리한다.

```text
1. ChatRoom.status
2. RoomMembership.status
3. ChatMessage.visibility
4. WebSocketSession.runtimeStatus
5. ChatRoom.operationStatus
6. ChatPeriod
7. OutboxEvent
```

각 상태는 서로 다른 생명주기를 가진다.

```text
ChatRoom
  - 방 자체의 생성, 활성, 종료 상태

RoomMembership
  - 특정 userId가 특정 roomId에 참여 중인지 여부

ChatMessage
  - 메시지가 사용자에게 표시 가능한지 여부

WebSocketSession
  - 서버 런타임에서 관리되는 연결 및 구독 상태

ChatRoom.operationStatus
  - 운영자 또는 시스템 개입 필요 여부

ChatPeriod
  - 특정 userId가 특정 roomId에 입장해 있던 메시지 조회 가능 구간

OutboxEvent
  - DB에 확정된 상태 변경을 WebSocket 또는 broker로 전달하기 위한 이벤트 기록
```

---

## 4. ChatRoom 상태전이도

### 4.1 상태

```text
ACTIVE
CLOSED
```

| 상태 | 의미 |
|---|---|
| `ACTIVE` | 일반 USER가 목록 조회, 상세 조회, 참여, 메시지 조회, 메시지 전송을 할 수 있는 상태 |
| `CLOSED` | 종료된 대화방. 일반 USER 목록/상세/메시지 조회에서 제외되는 상태 |

---

### 4.2 운영 상태

`ChatRoom.status`는 room의 사용자 기능 가능 여부를 나타낸다.

`ChatRoom.operationStatus`는 운영자 또는 시스템 개입 필요 여부를 나타낸다.

```text
ChatRoom.status:
  ACTIVE
  CLOSED

ChatRoom.operationStatus:
  NORMAL
  SYSTEM_MANAGED
```

| 운영 상태 | 의미 |
|---|---|
| `NORMAL` | 일반 운영 상태 |
| `SYSTEM_MANAGED` | owner 비활성화 등으로 운영자 조치가 필요한 상태 |

`SYSTEM_MANAGED` room은 서비스 정책에 따라 신규 참여, 메시지 전송, 조회가 제한될 수 있다.

`OPERATOR`는 `SYSTEM_MANAGED` room에 대해 owner transfer 또는 closeRoom을 수행할 수 있다.

---

### 4.3 상태전이

```text
[NOT_CREATED]
  -- createRoom(authenticated user) -->
ACTIVE

ACTIVE
  -- closeRoom(owner only) -->
CLOSED

CLOSED
  -- terminal -->
CLOSED
```

---

### 4.4 createRoom

```text
전이:
  NOT_CREATED -> ACTIVE

actor:
  인증된 USER

조건:
  - JWT 검증 성공
  - jwt.sub를 userId로 추출 가능
  - 요청 payload 유효

성공 시:
  - room 생성
  - room.status = ACTIVE
  - room.operationStatus = NORMAL
  - room.ownerUserId = authenticatedUser.userId
  - 생성자의 membership.status = ACTIVE
  - ROOM_CREATED 이벤트 발행 가능
```

방 생성 요청은 중복 생성 방지를 위해 `clientRequestId`를 포함할 수 있다.

```text
clientRequestId
  - 프론트엔드가 생성한다.
  - 동일 방 생성 요청을 재시도할 때 동일한 값을 사용한다.
  - 사용자가 새로운 방 생성 요청을 하면 새로운 값을 생성한다.
```

서버는 다음 조합을 중복 방지 기준으로 사용할 수 있다.

```text
ownerUserId + clientRequestId
```

여기서 `ownerUserId`는 요청 body에서 받지 않고 JWT `sub`에서 추출한다.

```text
ownerUserId = jwt.sub
```

이미 동일 조합으로 생성된 room이 있으면 새 room을 만들지 않고 기존 room을 반환한다.

---

### 4.5 closeRoom

```text
전이:
  ACTIVE -> CLOSED

actor:
  room owner

조건:
  - JWT 검증 성공
  - room.status == ACTIVE
  - room.ownerUserId == authenticatedUser.userId

성공 시:
  - room.status = CLOSED
  - closedAt 기록
  - room에 열려 있는 ChatPeriod 종료
  - 이후 일반 USER의 join/send/read 차단
  - 해당 room의 WebSocket subscription 종료 가능
  - WebSocket 구독자에게 ROOM_CLOSED 이벤트 발행 가능
```

owner는 일반 `leaveRoom`으로 방을 나가지 않는다.

```text
owner가 방 이용을 종료하려면 closeRoom을 호출한다.
owner 없는 ACTIVE room은 허용하지 않는다.
```

이미 `CLOSED` 상태인 room에 대해 `closeRoom` 요청이 들어온 경우는 다음 응답을 기본값으로 둔다.

```text
이미 CLOSED인 room에 closeRoom 요청
  -> 409 Conflict
```

---

### 4.6 owner 비활성화 처리

`ACTIVE` room은 유효한 owner를 가져야 한다.

owner 계정이 탈퇴, 삭제, 정지 등으로 owner 역할을 수행할 수 없는 경우 room의 `operationStatus`는 `SYSTEM_MANAGED`로 전환될 수 있다.

```text
NORMAL
  -- owner unavailable -->
SYSTEM_MANAGED
```

`SYSTEM_MANAGED` room은 `OPERATOR`에 의해 다음 중 하나로 처리될 수 있다.

```text
- 다른 ACTIVE member에게 owner transfer
- closeRoom
```

owner 없는 `ACTIVE` room은 허용하지 않는다.

---

### 4.7 CLOSED room 접근 정책

`CLOSED` room은 일반 USER API에서 노출하지 않는다.

| room.status | USER 목록 조회 | USER 상세 조회 | USER 메시지 조회 | USER 참여 | USER 메시지 전송 |
|---|---:|---:|---:|---:|---:|
| `ACTIVE` | 가능 | 가능 | 가능 | 가능 | 가능 |
| `CLOSED` | 불가 | 불가 | 불가 | 불가 | 불가 |

일반 USER가 `CLOSED` room을 조회하는 경우 기본 응답은 다음으로 한다.

```text
USER가 CLOSED room 상세 조회
  -> 404 Not Found

USER가 CLOSED room 메시지 조회
  -> 404 Not Found

USER가 CLOSED room 참여 요청
  -> 404 Not Found

USER가 CLOSED room 메시지 전송 요청
  -> 404 Not Found
```

본 문서에서는 일반 USER에게 종료된 room의 존재를 숨기는 방향으로 `404 Not Found`를 기본값으로 둔다.

---

## 5. RoomMembership 상태전이도

### 5.1 상태

```text
NONE
ACTIVE
LEFT
```

| 상태 | 의미 |
|---|---|
| `NONE` | 아직 해당 room에 참여 이력이 없음. DB row가 없을 수 있는 개념 상태 |
| `ACTIVE` | 현재 room에 참여 중 |
| `LEFT` | 과거에 참여했지만 현재 나간 상태 |

---

### 5.2 상태전이

```text
[NONE]
  -- joinRoom(room ACTIVE) -->
ACTIVE

ACTIVE
  -- leaveRoom(non-owner user) -->
LEFT

LEFT
  -- joinRoom(room ACTIVE) -->
ACTIVE
```

---

### 5.3 joinRoom

```text
전이:
  NONE -> ACTIVE
  LEFT -> ACTIVE
  ACTIVE -> ACTIVE

actor:
  인증된 USER

조건:
  - JWT 검증 성공
  - room.status == ACTIVE

성공 시:
  - membership.status = ACTIVE
  - joinedAt 또는 rejoinedAt 기록
  - 새로운 ChatPeriod 생성
  - ROOM_JOINED 이벤트 발행 가능
```

이미 참여 중인 사용자가 다시 `joinRoom`을 호출한 경우는 중복 요청으로 보고 성공 처리한다.

```text
ACTIVE 상태에서 joinRoom
  -> 상태 변화 없음
  -> 성공 응답
```

이 처리는 서버가 재시도한다는 의미가 아니다.

클라이언트 또는 네트워크 문제로 같은 요청이 여러 번 도착해도 부수 효과가 중복되지 않도록 하는 idempotent 처리다.

---

### 5.4 leaveRoom

```text
전이:
  ACTIVE -> LEFT

actor:
  room owner가 아닌 참여자

조건:
  - JWT 검증 성공
  - membership.status == ACTIVE
  - room.ownerUserId != authenticatedUser.userId

성공 시:
  - membership.status = LEFT
  - leftAt 기록
  - 현재 ChatPeriod 종료
  - ROOM_LEFT 이벤트 발행 가능
```

이미 `LEFT` 상태인 사용자가 다시 `leaveRoom`을 호출한 경우는 중복 요청으로 보고 성공 처리할 수 있다.

```text
LEFT 상태에서 leaveRoom
  -> 상태 변화 없음
  -> 성공 응답
```

owner가 `leaveRoom`을 호출하는 것은 허용하지 않는다.

```text
owner leaveRoom 요청
  -> 403 Forbidden
  -> closeRoom 사용 필요
```

---

## 6. ChatPeriod

### 6.1 성격

`ChatPeriod`는 사용자가 room에 입장해 있던 메시지 조회 가능 구간이다.

`RoomMembership`은 현재 참여 상태를 나타내고, `ChatPeriod`는 메시지 조회 범위를 판단한다.

```text
ChatPeriod
  - roomId
  - userId
  - joinedSequence
  - leftSequence nullable
  - joinedAt
  - leftAt nullable
```

사용자는 현재 `ChatPeriod`가 시작된 이후 생성된 메시지만 조회할 수 있다.

재입장 시 새로운 `ChatPeriod`가 생성되며, 이전 `ChatPeriod` 또는 퇴장 중 생성된 메시지는 현재 `ChatPeriod` 조회 범위에 포함되지 않는다.

---

### 6.2 joinRoom과 ChatPeriod

`joinRoom` 성공 시 새로운 `ChatPeriod`가 생성된다.

```text
joinedSequence = joinRoom 처리 시점의 room.lastMessageSequence
leftSequence = null
```

`joinedSequence`는 사용자가 입장하기 전에 이미 생성되어 있던 메시지를 조회 범위에서 제외하기 위한 기준이다.

---

### 6.3 leaveRoom과 ChatPeriod

`leaveRoom` 성공 시 현재 열려 있는 `ChatPeriod`가 닫힌다.

```text
leftSequence = leaveRoom 처리 시점의 room.lastMessageSequence
```

`closeRoom` 성공 시 해당 room에 열려 있는 모든 `ChatPeriod`가 닫힌다.

```text
leftSequence = closeRoom 처리 시점의 room.lastMessageSequence
```

---

### 6.4 메시지 조회 범위

일반 USER의 메시지 조회는 현재 `ChatPeriod` 범위 안에서만 가능하다.

```text
message.roomSequence > chatPeriod.joinedSequence
AND (
  chatPeriod.leftSequence IS NULL
  OR message.roomSequence <= chatPeriod.leftSequence
)
```

현재 열린 `ChatPeriod`가 없는 사용자는 해당 room의 메시지를 조회할 수 없다.

---

## 7. ChatMessage 상태전이도

### 7.1 메시지 상태와 속성 구분

메시지 수정은 메시지 상태가 아니다.

```text
메시지 수정
  -> content, editedAt, editVersion 변경
  -> visibility는 유지
```

따라서 `EDITED`라는 상태를 만들지 않는다.

메시지 삭제는 사용자에게 표시되는 방식이 바뀌므로 `visibility` 전이로 관리한다.

```text
MessageVisibility:
  VISIBLE
  DELETED_BY_USER
```

---

### 7.2 상태전이

```text
[NOT_CREATED]
  -- sendMessage(room ACTIVE && membership ACTIVE) -->
VISIBLE

VISIBLE
  -- editMessage(sender only) -->
VISIBLE

VISIBLE
  -- deleteMessage(sender only) -->
DELETED_BY_USER

DELETED_BY_USER
  -- terminal -->
DELETED_BY_USER
```

---

### 7.3 sendMessage

```text
전이:
  NOT_CREATED -> VISIBLE

actor:
  ACTIVE membership을 가진 USER

조건:
  - JWT 검증 성공
  - room.status == ACTIVE
  - membership.status == ACTIVE
  - 요청 content 유효
  - clientMessageId 존재

성공 시:
  - message 생성
  - message.visibility = VISIBLE
  - roomSequence 부여
  - MESSAGE_SENT 이벤트 발행
  - WebSocket 구독자에게 메시지 broadcast
```

메시지 전송 요청은 `clientMessageId`를 포함한다.

```text
clientMessageId
  - 프론트엔드가 생성한다.
  - 사용자가 메시지 하나를 보내려는 의도를 식별한다.
  - 같은 메시지 전송 요청을 재시도할 때 동일한 값을 사용한다.
  - 사용자가 새 메시지를 보내면 새 값을 생성한다.
```

`clientMessageId`는 content 기반으로 만들지 않는다.

```text
금지:
  - content hash
  - Date.now() 단독 사용

권장:
  - UUID
  - ULID
  - UUID v7
  - 충분한 충돌 저항성을 가진 랜덤 ID
```

브라우저에서는 다음 방식으로 생성할 수 있다.

```text
crypto.randomUUID()
```

서버는 다음 조합을 unique로 관리한다.

```text
roomId + senderUserId + clientMessageId
```

여기서 `senderUserId`는 요청 body에서 받지 않고 JWT `sub`에서 추출한다.

```text
senderUserId = jwt.sub
```

동일 조합의 메시지가 이미 존재하면 새 메시지를 생성하지 않고 기존 메시지를 반환한다.

```text
첫 요청:
  clientMessageId = abc
  -> message 생성
  -> messageId = msg_100
  -> roomSequence = 351

재시도 요청:
  clientMessageId = abc
  -> 기존 message 반환
  -> messageId = msg_100
  -> roomSequence = 351
```

동일 `clientMessageId`지만 요청 payload가 다르면 클라이언트 버그 또는 key 재사용으로 본다.

```text
clientMessageId 동일
content 다름
  -> 409 Conflict
```

응답에는 다음 값을 포함한다.

```text
messageId
clientMessageId
roomId
roomSequence
senderUserId
content
visibility
sentAt
```

프론트엔드는 `clientMessageId`를 기준으로 pending 말풍선과 서버 응답 메시지를 매칭한다.

---

### 7.4 editMessage

```text
전이:
  VISIBLE -> VISIBLE

actor:
  메시지 작성자

조건:
  - JWT 검증 성공
  - room.status == ACTIVE
  - message.visibility == VISIBLE
  - message.senderUserId == authenticatedUser.userId
  - 수정 가능한 시간/정책 조건 충족

성공 시:
  - content 변경
  - editedAt 기록
  - editVersion 증가
  - MESSAGE_EDITED 이벤트 발행 가능
```

메시지 수정은 상태 전이가 아니라 속성 변경이다.

```text
변경되는 값:
  - content
  - editedAt
  - editVersion
```

동시 수정 충돌을 막으려면 idempotencyKey보다 version control을 사용한다.

```text
요청 시 editVersion 또는 expectedVersion 전달
현재 editVersion과 다르면 409 Conflict
```

`editMessage`는 메시지 작성자만 요청할 수 있다.

단, 서비스 정책에 따라 수정 가능 시간, 메시지 상태, 신고/제재 상태 등의 추가 조건이 적용될 수 있다.

---

### 7.5 deleteMessage

```text
전이:
  VISIBLE -> DELETED_BY_USER

actor:
  메시지 작성자

조건:
  - JWT 검증 성공
  - room.status == ACTIVE
  - message.visibility == VISIBLE
  - message.senderUserId == authenticatedUser.userId

성공 시:
  - message.visibility = DELETED_BY_USER
  - deletedAt 기록
  - MESSAGE_DELETED_BY_USER 이벤트 발행 가능
```

삭제된 메시지는 물리 삭제하지 않는다.

```text
유지:
  - messageId
  - roomId
  - roomSequence
  - senderUserId
  - createdAt

변경:
  - visibility = DELETED_BY_USER
  - deletedAt 기록
```

저장 데이터는 감사와 정합성을 위해 유지한다.

일반 USER 응답에서는 삭제된 메시지의 content를 노출하지 않는다.

응답 content는 null 또는 삭제 안내 placeholder로 내려줄 수 있다.

삭제된 메시지 응답 예시는 다음과 같다.

```json
{
  "messageId": "msg_100",
  "roomSequence": 351,
  "visibility": "DELETED_BY_USER",
  "content": null,
  "deletedAt": "2026-07-06T13:10:00+09:00"
}
```

`DELETED_BY_USER` 상태의 메시지를 다시 삭제하는 요청은 상태 변화 없이 성공으로 처리한다.

```text
DELETED_BY_USER 상태에서 deleteMessage
  -> 상태 변화 없음
  -> 성공 응답
```

---

## 8. roomSequence 관리 원칙

### 8.1 목적

`roomSequence`는 특정 room 안에서 메시지 순서를 보장하기 위한 값이다.

```text
messageId
  - 전체 시스템에서 메시지를 식별하는 ID

roomSequence
  - 특정 room 내부에서 메시지 순서를 나타내는 값
```

메시지 조회와 WebSocket 이벤트 정렬은 `roomSequence ASC`를 기준으로 한다.

```text
ORDER BY roomSequence ASC
```

---

### 8.2 다중 서버 환경에서의 관리

서비스 서버가 여러 개일 수 있으므로 `roomSequence`를 애플리케이션 메모리에서 관리하지 않는다.

```text
금지:
  - 각 서버 인스턴스가 메모리에서 roomSequence 증가

권장:
  - DB transaction 안에서 room 단위로 sequence 증가
```

메시지 생성 시 흐름은 다음과 같다.

```text
1. JWT 검증
2. room.status == ACTIVE 확인
3. membership.status == ACTIVE 확인
4. clientMessageId 중복 여부 확인
5. room의 lastMessageSequence를 DB transaction 안에서 증가
6. 증가된 값을 message.roomSequence로 사용
7. message insert
8. transaction commit
```

개념적으로 다음 방식이다.

```text
chat_room.lastMessageSequence = chat_room.lastMessageSequence + 1
message.roomSequence = 증가된 lastMessageSequence
```

반드시 다음 unique 제약이 필요하다.

```text
roomId + roomSequence unique
```

또한 메시지 중복 저장 방지를 위해 다음 unique 제약도 필요하다.

```text
roomId + senderUserId + clientMessageId unique
```

중복 요청으로 기존 메시지를 반환하는 경우에는 `roomSequence`를 새로 증가시키지 않는다.

---

### 8.3 roomSequence와 DB ID 구분

`roomSequence`는 DB auto increment 값으로 대체하지 않는다.

`roomSequence`는 room 내부 메시지 순서를 나타내는 도메인 sequence이며, room 단위로 증가한다.

```text
chat_message.id
  - DB row 식별자

messageId
  - 외부에 노출 가능한 메시지 식별자

roomSequence
  - room 내부 메시지 정렬 및 복구 기준
```

message 저장과 `room.lastMessageSequence` 증가는 같은 DB transaction 안에서 처리되어야 한다.

transaction이 실패하면 message 저장과 `lastMessageSequence` 증가가 함께 rollback되어야 한다.

`roomSequence`는 저장에 성공한 메시지 기준으로 연속 증가한다.

검증 실패, 중복 요청, 저장 실패로 메시지가 생성되지 않은 경우 `roomSequence`를 소비하지 않는다.

---

### 8.4 동시성 원칙

같은 room에 대한 메시지 생성과 room 종료는 일관된 순서로 처리되어야 한다.

서버는 다음 불변식을 보장해야 한다.

```text
- CLOSED room에는 새 메시지가 생성되지 않는다.
- 하나의 room 안에서 roomSequence는 중복되지 않는다.
- 같은 clientMessageId 요청은 하나의 메시지만 생성한다.
- 중복 요청으로 기존 메시지를 반환하는 경우 roomSequence를 새로 소비하지 않는다.
```

room 종료 요청과 메시지 전송 요청이 동시에 처리되는 경우, 서버는 둘 중 하나의 순서를 확정해야 한다.

```text
sendMessage가 먼저 확정된 경우:
  - 메시지는 저장되고 roomSequence를 가진다.
  - 이후 closeRoom이 적용된다.

closeRoom이 먼저 확정된 경우:
  - 이후 sendMessage는 실패한다.
```

---

### 8.5 조회 기준

채팅 내역 pagination은 시간보다 sequence 기준이 안정적이다.

```text
GET /rooms/{roomId}/messages?beforeSequence=1000&limit=50
GET /rooms/{roomId}/messages?afterSequence=1000&limit=50
```

기본 정렬은 다음으로 한다.

```text
roomSequence ASC
```

최근 메시지 조회 후 클라이언트에서 역순 표시가 필요하면 응답 규칙을 별도로 정의한다.

---

## 9. WebSocketSession 상태전이도

### 9.1 성격

WebSocket 상태는 DB에 저장하는 영속 상태가 아니다.

```text
DB에 저장하는 것:
  - room
  - membership
  - message

서버 런타임에서 관리하는 것:
  - WebSocket connection
  - room subscription
```

WebSocket 연결/구독 상태는 서버 메모리 또는 WebSocket broker가 관리한다.

---

### 9.2 상태

```text
DISCONNECTED
CONNECTED
SUBSCRIBED
```

| 상태 | 의미 |
|---|---|
| `DISCONNECTED` | WebSocket 연결 없음 |
| `CONNECTED` | WebSocket 연결은 되었지만 room 구독 전 |
| `SUBSCRIBED` | 특정 room 이벤트를 수신 중 |

---

### 9.3 상태전이

```text
DISCONNECTED
  -- connect(valid JWT) -->
CONNECTED

CONNECTED
  -- subscribeRoom(room ACTIVE && membership ACTIVE) -->
SUBSCRIBED

SUBSCRIBED
  -- unsubscribeRoom or disconnect -->
CONNECTED or DISCONNECTED

CONNECTED
  -- disconnect -->
DISCONNECTED
```

---

### 9.4 연결 관리 기준

WebSocket 연결은 `userId` 단독이 아니라 `connectionId` 기준으로 관리한다.

한 사용자가 여러 연결을 가질 수 있기 때문이다.

```text
같은 userId
  - PC 브라우저 탭 1
  - PC 브라우저 탭 2
  - 모바일 앱
```

서버 런타임에서는 다음 인덱스를 관리할 수 있다.

```text
connectionId -> userId
connectionId -> subscribedRoomIds
roomId -> connectionIds
userId -> connectionIds
```

단일 서버 환경에서는 메모리 Map으로 관리할 수 있다.

다중 WebSocket 서버 환경에서는 각 서버가 자신의 local connection만 관리하고, room 이벤트는 broker 또는 pub/sub를 통해 전파한다.

```text
message 저장 성공
  -> MESSAGE_SENT 이벤트 publish
  -> 각 WebSocket 서버가 roomId를 구독 중인 local connection에 전송
```

---

### 9.5 subscribeRoom 조건

room 구독 조건은 다음과 같다.

```text
JWT 검증 성공
room.status == ACTIVE
room.operationStatus 구독 허용 상태
membership.status == ACTIVE
현재 열린 ChatPeriod 존재
```

`CLOSED` room은 일반 USER가 구독할 수 없다.

```text
CLOSED room subscribe 요청
  -> 404 Not Found
```

본 문서에서는 USER에게 CLOSED room 존재를 숨기는 정책에 맞춰 `404 Not Found`를 기본값으로 둔다.

---

### 9.6 WebSocket 인증 및 재연결 원칙

WebSocket 연결 시 JWT를 검증한다.

구독 요청 시마다 `room.status`, `room.operationStatus`, `membership.status`, `ChatPeriod` 유효성을 다시 검증한다.

JWT가 만료되었거나 사용자의 membership 상태가 변경된 경우 서버는 해당 connection 또는 subscription을 종료할 수 있다.

WebSocket 연결 종료는 membership 상태를 변경하지 않는다.

클라이언트는 재연결 후 기존 subscription을 자동으로 신뢰하지 않고 room 구독을 다시 요청한다.

클라이언트는 마지막으로 처리한 `roomSequence` 이후 메시지를 조회하여 누락 이벤트를 복구할 수 있다.

```text
GET /rooms/{roomId}/messages?afterSequence={lastReceivedSequence}
```

WebSocket 이벤트는 중복 또는 유실될 수 있으므로 클라이언트는 `roomSequence` 기준으로 정렬, 중복 제거, 누락 복구를 수행한다.

서버는 특정 connection이 메시지를 정상적으로 소비하지 못하는 경우 해당 connection을 종료할 수 있다.

connection 종료는 membership 상태를 변경하지 않는다.

---

## 10. 중복 요청과 idempotency 정책

### 10.1 기본 원칙

중복 요청 정책은 서버가 요청을 재시도한다는 의미가 아니다.

```text
의미:
  같은 요청이 서버에 여러 번 도착해도 같은 부수 효과가 여러 번 발생하지 않도록 한다.
```

중복 요청은 다음 상황에서 발생할 수 있다.

```text
- 네트워크 타임아웃
- 응답 유실
- 클라이언트 자동 재시도
- 사용자의 빠른 중복 클릭
- WebSocket 재연결 후 재전송
```

---

### 10.2 기능별 정책

| 기능 | 중복 방지 방식 |
|---|---|
| `createRoom` | `clientRequestId` 사용 권장 |
| `joinRoom` | 현재 membership 상태 기준으로 idempotent 처리 |
| `leaveRoom` | 현재 membership 상태 기준으로 idempotent 처리 |
| `sendMessage` | `clientMessageId` 필수 |
| `editMessage` | `editVersion` 기반 충돌 방지 |
| `deleteMessage` | 현재 visibility 상태 기준으로 idempotent 처리 가능 |
| `closeRoom` | 이미 CLOSED면 `409 Conflict` |

---

### 10.3 clientMessageId

```text
생성 주체:
  프론트엔드

사용 대상:
  메시지 전송 요청

생성 시점:
  사용자가 메시지 전송을 시도하는 순간

재시도 시:
  동일 메시지 전송 요청에는 동일 clientMessageId 사용

새 메시지 전송 시:
  새로운 clientMessageId 생성
```

서버의 unique 기준:

```text
roomId + senderUserId + clientMessageId
```

---

### 10.4 clientRequestId

```text
생성 주체:
  프론트엔드

사용 대상:
  방 생성 등 command 요청

생성 시점:
  사용자가 방 생성 요청을 시도하는 순간

재시도 시:
  동일 방 생성 요청에는 동일 clientRequestId 사용

새 방 생성 시:
  새로운 clientRequestId 생성
```

서버의 unique 기준:

```text
ownerUserId + clientRequestId
```

---

## 11. 상태별 허용 동작

### 11.1 ChatRoom 기준

| room.status | 목록 조회 | 상세 조회 | 참여 | 메시지 조회 | 메시지 전송 | 수정/삭제 | 종료 |
|---|---:|---:|---:|---:|---:|---:|---:|
| `ACTIVE` | 가능 | 가능 | 가능 | 가능 | 참여자 가능 | 작성자 가능 | owner 가능 |
| `CLOSED` | 불가 | 불가 | 불가 | 불가 | 불가 | 불가 | 불가 |

`CLOSED` room은 일반 USER API에서 숨긴다.

`ChatRoom.operationStatus`가 `SYSTEM_MANAGED`인 경우 서비스 정책에 따라 신규 참여, 메시지 전송, 조회가 제한될 수 있다.

---

### 11.2 RoomMembership 기준

| membership.status | 메시지 조회 | 메시지 전송 | leaveRoom | joinRoom |
|---|---:|---:|---:|---:|
| `NONE` | 불가 | 불가 | 불가 | 가능 |
| `ACTIVE` | 가능 | 가능 | 가능 | 성공 처리 |
| `LEFT` | 불가 | 불가 | 성공 처리 가능 | 가능 |

단, 모든 경우에 `room.status == ACTIVE` 조건이 우선한다.

메시지 조회 가능 범위는 `RoomMembership`만으로 판단하지 않고 현재 열린 `ChatPeriod` 기준으로 판단한다.

---

### 11.3 ChatMessage 기준

| message.visibility | 일반 조회 | 수정 | 삭제 |
|---|---:|---:|---:|
| `VISIBLE` | 가능 | 작성자 가능 | 작성자 가능 |
| `DELETED_BY_USER` | placeholder 또는 제외 | 불가 | 성공 처리 가능 |

---

## 12. 데이터 불변식

서버는 다음 데이터 불변식을 보장해야 한다.

```text
- roomId는 유일하다.
- messageId는 유일하다.
- 하나의 room 안에서 roomSequence는 유일하다.
- roomSequence는 저장에 성공한 메시지 기준으로 연속 증가한다.
- 같은 roomId + senderUserId + clientMessageId는 하나의 message만 가리킨다.
- 하나의 ACTIVE room 안에서 같은 userId의 ACTIVE membership은 하나만 존재한다.
- ACTIVE room의 ACTIVE membership은 하나의 열린 ChatPeriod를 가진다.
- 열린 ChatPeriod는 leftSequence가 없다.
- LEFT membership은 열린 ChatPeriod를 가질 수 없다.
- ChatPeriod.joinedSequence는 입장 처리 시점의 room.lastMessageSequence다.
- ACTIVE room은 유효한 owner를 가져야 한다.
- owner는 해당 room의 ACTIVE membership을 가져야 한다.
- CLOSED room에는 새 message, membership, subscription이 생성되지 않는다.
- CLOSED room에는 열린 ChatPeriod가 남아 있을 수 없다.
- message.senderUserId는 메시지 생성 시점에 해당 room의 ACTIVE member여야 한다.
- 일반 USER 메시지 조회는 현재 ChatPeriod의 joinedSequence 이후 메시지만 대상으로 한다.
- message 저장 성공 시 같은 DB transaction 안에서 outbox_event가 함께 저장되어야 한다.
```

---

## 13. 실패 응답 기준

| 상황 | 응답 |
|---|---:|
| JWT 없음 | 401 |
| JWT signature 오류 | 401 |
| JWT 만료 | 401 |
| room 없음 | 404 |
| USER가 CLOSED room 조회 | 404 |
| USER가 CLOSED room 메시지 조회 | 404 |
| CLOSED room 참여 요청 | 404 |
| CLOSED room 메시지 전송 | 404 |
| membership 없음 | 403 |
| membership LEFT | 403 |
| owner가 아닌데 closeRoom 요청 | 403 |
| owner가 leaveRoom 요청 | 403 |
| 이미 CLOSED인데 closeRoom 요청 | 409 |
| 동일 clientMessageId에 다른 payload 사용 | 409 |
| editVersion 충돌 | 409 |

기본 구분은 다음과 같다.

```text
인증 실패
  -> 401

권한 부족
  -> 403

대상 없음 또는 일반 USER에게 숨기는 대상
  -> 404

현재 상태와 요청 충돌
  -> 409
```

---

## 14. 이벤트 기록 및 전달 원칙

### 14.1 OutboxEvent

채팅 메시지와 상태 변경의 최종 기준은 DB에 저장된 room, membership, `ChatPeriod`, message 상태다.

WebSocket broadcast는 실시간 전달 경로이며, DB에 확정된 상태를 대체하지 않는다.

메시지 전송이 성공하는 경우 서버는 같은 DB transaction 안에서 다음 두 데이터를 함께 저장한다.

```text
1. chat_message row
2. outbox_event row
```

`outbox_event`는 WebSocket broadcast 또는 broker publish가 필요하다는 사실을 기록하는 데이터다.

실제 WebSocket broadcast 또는 broker publish는 DB transaction commit 이후 별도 relay/worker가 수행할 수 있다.

서버 장애가 발생하더라도 DB에 저장된 `outbox_event`를 기준으로 이벤트를 재발행할 수 있어야 한다.

각 이벤트는 `eventId`를 가진다.

WebSocket 이벤트는 중복 전달될 수 있으므로 수신자는 `eventId` 또는 `roomSequence` 기준으로 중복 처리를 할 수 있어야 한다.

---

### 14.2 상태전이 이벤트

상태전이 성공 시 다음 이벤트를 발행할 수 있다.

```text
ROOM_CREATED
ROOM_CLOSED
ROOM_JOINED
ROOM_LEFT
MESSAGE_SENT
MESSAGE_EDITED
MESSAGE_DELETED_BY_USER
```

각 이벤트는 최소한 다음 정보를 가진다.

```text
eventId
eventType
roomId
actorUserId
occurredAt
payload
```

메시지 관련 이벤트에는 다음을 포함한다.

```text
messageId
clientMessageId
roomSequence
visibility
```

이벤트는 WebSocket broadcast, 로그 적재, 후속 처리에 사용할 수 있다.

---

## 15. 최종 상태전이 요약

### 15.1 ChatRoom.status

```text
[NOT_CREATED]
  -- createRoom -->
ACTIVE

ACTIVE
  -- closeRoom(owner only) -->
CLOSED
```

---

### 15.2 ChatRoom.operationStatus

```text
NORMAL
  -- owner unavailable -->
SYSTEM_MANAGED

SYSTEM_MANAGED
  -- owner transfer or operator decision -->
NORMAL or CLOSED
```

---

### 15.3 RoomMembership

```text
[NONE]
  -- joinRoom -->
ACTIVE

ACTIVE
  -- leaveRoom(non-owner) -->
LEFT

LEFT
  -- joinRoom -->
ACTIVE
```

---

### 15.4 ChatPeriod

```text
[NOT_OPENED]
  -- joinRoom -->
OPEN

OPEN
  -- leaveRoom -->
CLOSED
```

---

### 15.5 ChatMessage.visibility

```text
[NOT_CREATED]
  -- sendMessage -->
VISIBLE

VISIBLE
  -- editMessage -->
VISIBLE

VISIBLE
  -- deleteMessage -->
DELETED_BY_USER
```

---

### 15.6 WebSocketSession.runtimeStatus

```text
DISCONNECTED
  -- connect(valid JWT) -->
CONNECTED

CONNECTED
  -- subscribeRoom(room ACTIVE && membership ACTIVE) -->
SUBSCRIBED

SUBSCRIBED
  -- unsubscribe or disconnect -->
CONNECTED or DISCONNECTED
```

---

## 16. 최종 원칙

1. JWT는 사용자가 누구인지 식별하는 데 사용한다.
2. room owner 여부는 JWT role이 아니라 `room.ownerUserId == jwt.sub`로 판단한다.
3. `ChatRoom.status`는 `ACTIVE`, `CLOSED`만 사용한다.
4. `ChatRoom.operationStatus`는 `NORMAL`, `SYSTEM_MANAGED`를 사용한다.
5. `SYSTEM_MANAGED`는 운영자 조치가 필요한 room 운영 상태다.
6. `CLOSED` room은 일반 USER 목록/상세/메시지 조회에서 제외한다.
7. `RoomMembership.status`는 `ACTIVE`, `LEFT`를 사용하고, 참여 이력이 없으면 `NONE` 개념 상태로 본다.
8. 메시지 조회 가능 범위는 `ChatPeriod` 기준으로 판단한다.
9. 사용자는 현재 `ChatPeriod` 시작 이후 생성된 메시지만 조회할 수 있다.
10. 메시지 수정은 상태 전이가 아니라 속성 변경이다.
11. 메시지 삭제는 `MessageVisibility` 전이로 관리한다.
12. 삭제된 메시지는 물리 삭제하지 않고 `DELETED_BY_USER`로 표시한다.
13. 일반 USER 응답에서는 삭제된 메시지의 content를 노출하지 않는다.
14. `roomSequence`는 room 단위 도메인 sequence이며 DB auto increment로 대체하지 않는다.
15. `roomSequence`는 DB transaction에서 room 단위로 부여한다.
16. 서비스 서버 메모리에서 `roomSequence`를 증가시키지 않는다.
17. `roomSequence`는 저장에 성공한 메시지 기준으로 연속 증가한다.
18. 메시지 전송 요청은 프론트엔드가 생성한 `clientMessageId`를 포함한다.
19. 서버는 `roomId + senderUserId + clientMessageId`를 unique로 관리한다.
20. 방 생성 요청은 프론트엔드가 생성한 `clientRequestId`를 포함할 수 있다.
21. 서버는 `ownerUserId + clientRequestId`를 unique로 관리할 수 있다.
22. message 저장과 `outbox_event` 저장은 같은 DB transaction 안에서 처리한다.
23. WebSocket broadcast는 실시간 전달 경로이며 DB 상태를 대체하지 않는다.
24. WebSocket 연결/구독 상태는 DB 영속 상태가 아니라 서버 런타임 상태다.
25. WebSocket 상태는 `connectionId` 기준으로 관리한다.
26. WebSocket 재연결 후 클라이언트는 `afterSequence` 기반으로 누락 메시지를 복구한다.
27. join/leave/delete는 현재 DB 상태를 기준으로 중복 요청을 안전하게 처리한다.
28. sendMessage는 `clientMessageId`로 중복 메시지 생성을 방지한다.
29. editMessage는 `editVersion`으로 동시 수정 충돌을 방지한다.
30. 상태전이 실패는 401, 403, 404, 409 기준으로 구분한다.
