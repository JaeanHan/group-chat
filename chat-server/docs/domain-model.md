# 오픈 채팅방 도메인 모델 명세

## 1. 목적

본 문서는 공용 오픈 채팅방 모듈의 핵심 도메인 모델, 상태, 관계, 불변식, 계산 기준을 정의한다.

도메인 모델은 다음 흐름으로 묶어 설명한다.

```text
채팅방
  -> 참여자
  -> 메시지
  -> 읽음 상태와 표시
  -> 실시간 연결
```

---

## 2. 모델 그룹

### 2.1 채팅방

```text
ChatRoom
```

### 2.2 참여자

```text
ChatRoomMembership
ChatParticipationPeriod
```

### 2.3 메시지

```text
ChatMessage
```

### 2.4 읽음 상태와 표시

```text
ChatReadCursor
ChatReadSession
```

### 2.5 실시간 연결

```text
WebSocketSession
```

---

## 3. 도메인 경계

room 단위 규칙은 `ChatRoom`을 기준으로 판단한다.

메시지는 별도 테이블 또는 repository로 저장할 수 있지만, 메시지 생성 여부와 `roomSequence` 부여는 `ChatRoom`의 현재 상태와 참여자 상태를 함께 확인해야 한다.

따라서 `ChatMessage`는 독립적으로 생성되는 모델이 아니라 `ChatRoom` 규칙 안에서 생성되는 모델이다.

```text
메시지 생성 조건:
  - room은 활성 상태여야 한다.
  - 사용자는 현재 참여 중이어야 한다.
  - 현재 열린 ChatParticipationPeriod가 존재해야 한다.
  - roomSequence는 room 단위로 부여한다.
```

---

## 4. 채팅방 모델

채팅방 모델은 방 자체의 생명주기와 방 운영 상태를 표현한다.

### 4.1 역할

`ChatRoom`은 오픈 채팅방 자체를 나타낸다.

`ChatRoom.status`는 일반 사용자 기능 가능 여부를 나타낸다.

`ChatRoom.operationStatus`는 운영자 또는 시스템 조치가 필요한 방 운영 상태를 나타낸다.

### 4.2 필드

```text
ChatRoom:
  roomId
  ownerUserId
  status
  operationStatus
  lastMessageSequence
  createdAt
  closedAt nullable
```

### 4.3 상태

```text
ChatRoom.status:
  ACTIVE
  CLOSED

ChatRoom.operationStatus:
  NORMAL
  SYSTEM_MANAGED
```

### 4.4 규칙

```text
- ACTIVE room은 유효한 owner를 가진다.
- owner는 해당 room의 ACTIVE ChatRoomMembership을 가진다.
- owner 없는 ACTIVE room은 허용하지 않는다.
- CLOSED room에는 새 message, membership, subscription이 생성되지 않는다.
- CLOSED room에는 열린 ChatParticipationPeriod가 남아 있을 수 없다.
```

### 4.5 관련 이벤트

```text
ROOM_CREATED
ROOM_CLOSED
ROOM_SYSTEM_MANAGED
ROOM_OWNER_TRANSFERRED
```

---

## 5. 참여자 모델

참여자 모델은 현재 참여 여부와 메시지 조회 가능 구간을 분리해서 표현한다.

### 5.1 역할

`ChatRoomMembership`은 특정 사용자가 현재 room에 참여 중인지 판단하는 현재 상태다.

`ChatParticipationPeriod`는 사용자가 room에 입장해 있던 메시지 조회 가능 구간이다.

재입장 시 새로운 `ChatParticipationPeriod`가 생성된다.

### 5.2 필드

```text
ChatRoomMembership:
  roomId
  userId
  status
  joinedAt
  leftAt nullable

ChatParticipationPeriod:
  roomId
  userId
  joinedSequence
  leftSequence nullable
  joinedAt
  leftAt nullable
```

### 5.3 상태

```text
ChatRoomMembership.status:
  ACTIVE
  LEFT
```

`ChatRoomMembership.status`는 저장된 membership row의 현재 상태만 나타낸다.

아직 참여 이력이 없는 사용자는 `ChatRoomMembership` row가 없을 수 있으며, 이 경우를 저장 상태 값으로 만들지 않는다.

### 5.4 규칙

```text
- owner는 일반 leaveRoom으로 room을 나가지 않는다.
- 하나의 ACTIVE room 안에서 같은 userId의 ACTIVE membership은 하나만 존재한다.
- ACTIVE room의 ACTIVE membership은 하나의 열린 ChatParticipationPeriod를 가진다.
- ChatParticipationPeriod는 사용자가 ACTIVE member가 되는 시점에 생성된다.
- createRoom에서는 owner가 ACTIVE member가 되므로 owner의 ChatParticipationPeriod를 생성한다.
- joinRoom에서는 일반 사용자가 ACTIVE member가 되므로 ChatParticipationPeriod를 생성한다.
- LEFT membership은 열린 ChatParticipationPeriod를 가질 수 없다.
- 열린 ChatParticipationPeriod는 leftSequence가 없다.
- ChatParticipationPeriod.joinedSequence는 입장 처리 시점의 room.lastMessageSequence다.
- ChatParticipationPeriod.joinedSequence는 입장 전 메시지를 조회 범위에서 제외하는 기준이다.
- ChatParticipationPeriod.leftSequence는 퇴장 후 메시지를 조회 범위에서 제외하는 기준이다.
- 일반 USER 메시지 조회는 현재 ChatParticipationPeriod의 joinedSequence 이후 메시지만 대상으로 한다.
```

메시지 조회 범위는 다음 기준을 따른다.

```text
message.roomSequence > chatParticipationPeriod.joinedSequence
AND (
  chatParticipationPeriod.leftSequence IS NULL
  OR message.roomSequence <= chatParticipationPeriod.leftSequence
)
```

### 5.5 관련 이벤트

```text
ROOM_JOINED
ROOM_LEFT
```

---

## 6. 메시지 모델

메시지 모델은 room 안에서 생성된 메시지와 메시지의 표시 상태를 표현한다.

### 6.1 역할

`ChatMessage`는 room 안에서 생성된 메시지다.

현재 core 범위의 메시지는 text content를 기준으로 한다.

파일, 이미지, 첨부 메시지는 향후 `ChatMessage.type` 확장과 `MessageAttachment` 모델로 다룬다.

### 6.2 필드

```text
ChatMessage:
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
  editedAt nullable
  deletedAt nullable
```

### 6.3 상태

```text
ChatMessage.type:
  TEXT

ChatMessage.visibility:
  VISIBLE
  DELETED_BY_USER
```

현재 core 범위에서는 `ChatMessage.type = TEXT`만 사용한다.

### 6.4 규칙

```text
- messageId는 전체 시스템에서 메시지를 식별한다.
- roomSequence는 특정 room 안에서 메시지 순서를 나타낸다.
- roomSequence는 room 내부 메시지 정렬, 페이지네이션, 누락 복구 기준이다.
- 하나의 room 안에서 roomSequence는 유일하다.
- roomSequence는 room마다 독립적으로 증가한다.
- 저장에 성공한 메시지 기준으로 roomSequence가 중복되거나 역전되면 안 된다.
- 같은 roomId + senderUserId + clientMessageId는 하나의 message만 가리킨다.
- clientMessageId는 같은 메시지 전송 요청이 중복 도착해도 메시지가 중복 생성되지 않도록 하는 클라이언트 생성 식별자다.
- ChatMessage.senderUserId는 메시지 생성 시점에 해당 room의 ACTIVE member여야 한다.
- editVersion은 메시지 변경 시 동시성 충돌을 감지하기 위한 버전 값이다.
- 삭제된 메시지는 물리 삭제하지 않는다.
- 일반 사용자 응답에서는 삭제된 메시지의 content를 노출하지 않는다.
```

### 6.5 관련 이벤트

```text
MESSAGE_SENT
MESSAGE_EDITED
MESSAGE_DELETED_BY_USER
```

---

## 7. 읽음 상태와 표시 모델

읽음 상태와 표시 모델은 사용자가 room에서 어디까지 읽었는지 저장하고, 메시지별로 몇 명이 읽었는지 계산하는 기준을 정의한다.

### 7.1 역할

`ChatReadCursor`는 사용자가 특정 room에서 어디까지 읽었는지 나타내는 source of truth다.

`ChatReadSession`은 특정 connection 또는 userId가 현재 room 화면을 보고 있어 read cursor 자동 갱신 대상인지 나타내는 런타임 상태다.

`ChatReadSession`은 DB에 저장하지 않는다.

같은 userId가 여러 connection으로 같은 room을 보고 있어도 read receipt count에서는 하나의 reader로 본다.

`readerKey`는 클라이언트가 메시지별 read receipt count를 계산할 때 사용하는 room 단위 익명 reader 식별자다.

`readerKey`는 서버가 `roomId + userId` 기준으로 생성하며, 같은 room의 같은 userId는 모든 connection에서 같은 `readerKey`를 가진다.

서버 source of truth는 `ChatReadCursor.userId`이며, `readerKey`는 클라이언트 전달용 계산 key다.

클라이언트는 `readerKey`를 UI에 노출하지 않고, read-by 사용자 목록을 만들기 위해 사용하지 않는다.

read-by 사용자 목록 노출은 초기 범위에 포함하지 않으며, 필요하면 권한 검사를 포함한 userId 기반 별도 조회로 설계한다.

### 7.2 필드

```text
ChatReadCursor:
  roomId
  userId
  lastReadSequence
  readAt

ChatReadSession:
  connectionId
  userId
  roomId
  runtimeStatus
```

### 7.3 상태

```text
ChatReadCursor.lastReadSequence:
  사용자가 해당 room에서 마지막으로 읽은 roomSequence

ChatReadSession.runtimeStatus:
  HIDDEN
  VISIBLE
```

`VISIBLE`은 사용자가 현재 room 화면을 보고 있어 read cursor 자동 갱신 대상인 상태다.

`HIDDEN`은 연결은 유지될 수 있지만 현재 room 화면을 보고 있지 않아 read cursor 자동 갱신 대상이 아닌 상태다.

### 7.4 규칙

```text
- 하나의 roomId + userId는 하나의 ChatReadCursor를 가진다.
- ChatReadCursor.lastReadSequence는 감소하지 않는다.
- ChatReadCursor.lastReadSequence는 현재 ChatParticipationPeriod 범위 안에서만 갱신된다.
- ChatReadSession은 ChatReadCursor 자동 갱신 여부를 판단한다.
- read receipt count는 작성자 본인을 제외한다.
- read receipt count는 현재 ACTIVE member snapshot을 기준으로 계산한다.
- LEFT member는 이후 read receipt count 계산 대상에 포함하지 않는다.
```

### 7.5 read cursor 갱신 기준

`ChatReadCursor.lastReadSequence`는 다음 경로로 갱신될 수 있다.

```text
- 클라이언트의 markRead 요청
- 서버가 VISIBLE ChatReadSession을 근거로 수행하는 자동 갱신
```

클라이언트는 새 메시지 수신마다 `markRead`를 호출하지 않는다.

`ChatReadSession.runtimeStatus = VISIBLE`인 사용자는 현재 room 화면을 보고 있는 사용자로 간주할 수 있다.

서버는 VISIBLE read session을 가진 사용자에게 새 메시지를 broadcast한 경우, 해당 사용자의 `ChatReadCursor.lastReadSequence`를 broadcast한 `roomSequence`까지 갱신할 수 있다.

클라이언트는 room enter, reconnect, foreground 복귀, scroll-to-bottom, visibility 복귀 시 `markRead`를 호출해 read cursor를 보정할 수 있다.

`ChatReadCursor.lastReadSequence`는 감소하지 않는다.

`ChatReadCursor.lastReadSequence`가 증가하면 서버는 갱신 전후 구간을 `READ_CURSOR_UPDATED`로 전파할 수 있다.

```text
readerKey:
  갱신된 ChatReadCursor의 room 단위 익명 reader 식별자

previousReadSequence:
  갱신 전 ChatReadCursor.lastReadSequence

lastReadSequence:
  갱신 후 ChatReadCursor.lastReadSequence
```

### 7.6 unread count

room별 unread count는 현재 사용자의 read cursor 이후 메시지 수로 계산한다.

```text
message.roomSequence > max(
  readCursor.lastReadSequence,
  currentChatParticipationPeriod.joinedSequence
)
```

현재 열린 `ChatParticipationPeriod`가 없는 사용자는 unread count를 계산하지 않는다.

### 7.7 read receipt count

메시지별 read receipt count는 현재 ACTIVE member snapshot에서 `lastReadSequence`가 메시지의 `roomSequence` 이상인 reader 수로 계산한다.

어떤 reader의 `lastReadSequence`가 특정 메시지의 `roomSequence` 이상이면, 그 reader는 해당 메시지를 읽은 것으로 본다.

작성자 본인은 read receipt count에서 제외한다. 작성자가 현재 ACTIVE member snapshot에 없으면 별도 제외 처리는 필요 없다.

```text
readers =
  current ACTIVE member read cursor snapshot

readReceiptCount =
  count(readers)
  where reader.readerKey != message.senderReaderKey
  and reader.lastReadSequence >= message.roomSequence

unreadReceiptCount =
  count(readers)
  where reader.readerKey != message.senderReaderKey
  and reader.lastReadSequence < message.roomSequence
```

클라이언트는 서버에서 받은 reader cursor snapshot과 메시지의 `senderReaderKey`를 사용해 메시지별 read receipt count를 계산할 수 있다.

서버는 `senderReaderKey`를 메시지 작성자의 `roomId + userId` 기준으로 계산해 내려준다.

클라이언트는 메시지 전송 요청에 `readerKey`를 보내지 않는다.

### 7.8 실시간 읽음 표시

`ChatReadCursor`가 갱신되면 서버는 그 결과를 WebSocket으로 클라이언트에게 알릴 수 있다.

이 이벤트는 서버에서 클라이언트로 전달되는 실시간 표시용 이벤트다.

클라이언트는 `READ_CURSOR_UPDATED.readerKey`를 기준으로 로컬 reader cursor snapshot을 갱신한다.

`lastReadSequence`가 로컬 snapshot의 해당 `readerKey` 값보다 작거나 같으면 중복 또는 오래된 이벤트로 보고 무시할 수 있다.

snapshot에 없는 `readerKey` 이벤트를 수신하면 해당 room의 snapshot이 stale한 것으로 보고 `getReadCursorSnapshot`으로 교체할 수 있다.

snapshot을 갱신한 뒤 클라이언트는 현재 표시 중인 메시지의 read receipt count를 다시 계산한다.

읽음 관련 WebSocket 이벤트는 영속 전달 보장 대상이 아니다.

읽음 이벤트가 유실되어도 `getReadCursorSnapshot`으로 reader cursor snapshot을 다시 받아 복구할 수 있다.

### 7.9 관련 이벤트

```text
READ_CURSOR_UPDATED
```

---

## 8. 실시간 연결 모델

실시간 연결 모델은 서버 런타임에서 관리하는 WebSocket 연결과 room 구독 상태를 표현한다.

### 8.1 역할

`WebSocketSession`은 서버 런타임에서 관리하는 연결 및 구독 상태다.

`WebSocketSession`은 DB 영속 상태가 아니다.

### 8.2 필드

```text
WebSocketSession:
  connectionId
  userId
  runtimeStatus
  subscribedRoomIds
```

### 8.3 상태

```text
WebSocketSession.runtimeStatus:
  DISCONNECTED
  CONNECTED
  SUBSCRIBED
```

### 8.4 규칙

```text
- WebSocket 연결 상태는 ChatRoomMembership 상태를 변경하지 않는다.
- 구독 요청 시 room 상태, 참여 상태, 참여 구간 유효성을 다시 검증한다.
- 같은 userId는 여러 connection을 가질 수 있다.
- connectionId를 기준으로 연결과 구독 상태를 관리한다.
```

---

## 9. 정책 확장 포인트

다음 항목은 서비스 정책에 따라 값 또는 조건이 달라질 수 있다.

```text
- 메시지 최대 길이
- 메시지 허용 문자
- 수정 가능 시간
- 수정 가능 횟수
- 삭제 가능 조건
- SYSTEM_MANAGED 상태에서 허용할 USER 동작
- owner transfer 가능 조건
- rate limit
- 신고/제재 연동 조건
- 메시지 보관 기간
```

---

## 10. 향후 확장 모델

현재 core 범위에는 포함하지 않지만 향후 확장 가능한 모델은 다음과 같다.

```text
MessageAttachment
  - 이미지, 파일, 첨부 메시지

Notification
  - push notification, mention notification, badge

Reaction
  - 메시지 이모지 반응

Moderation / Ban
  - 강퇴, 차단, 제재

Report
  - 신고

AuditLog
  - 장기 감사 로그

RoomReadProjection
  - 대형 room의 read receipt count 최적화
```
