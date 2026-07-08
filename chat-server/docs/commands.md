# 오픈 채팅방 Command 명세

## 1. 목적

본 문서는 공용 오픈 채팅방 모듈에서 상태를 변경하는 command의 처리 계약을 정의한다.

Command 명세는 다음 내용을 command별로 정리한다.

```text
- 누가 요청할 수 있는가
- 어떤 요청 필드가 필요한가
- 어떤 사전 조건을 만족해야 하는가
- 성공 시 어떤 도메인 상태가 변경되는가
- 어떤 이벤트가 발생할 수 있는가
- 중복 요청을 어떻게 처리하는가
- 실패를 어떤 기준으로 구분하는가
```

---

## 2. 공통 원칙

모든 command는 인증된 사용자 기준으로 처리한다.

요청 actor의 `userId`는 request body가 아니라 인증 token에서 얻는다.

Command 실패는 확정된 도메인 상태를 변경하지 않는다.

Command 성공 시 DB에 확정된 상태가 source of truth가 된다.

상태 변경이 확정된 뒤 필요한 경우 관련 이벤트를 만든다.

중복 요청은 command별 idempotency 기준에 따라 처리한다.

---

## 3. Command 작성 형식

각 command는 다음 형식으로 정의한다.

```text
목적
Actor
요청 필드
사전 조건
성공 시 상태 변화
관련 이벤트
Idempotency
실패 기준
```

---

## 4. Command 목록

### 4.1 채팅방

```text
createRoom
closeRoom
transferOwner
```

### 4.2 참여자

```text
joinRoom
leaveRoom
```

### 4.3 메시지

```text
sendMessage
editMessage
deleteMessage
```

### 4.4 읽음 상태와 표시

```text
markRead
```

### 4.5 실시간 연결

```text
subscribeRoom
unsubscribeRoom
```

---

## 5. 채팅방 Commands

### 5.1 createRoom

#### 목적

새 오픈 채팅방을 생성한다.

#### Actor

인증된 USER

#### 요청 필드

```text
name
clientRequestId optional
```

#### 사전 조건

```text
- 인증 token이 유효해야 한다.
- 요청 payload가 유효해야 한다.
```

#### 성공 시 상태 변화

```text
- ChatRoom 생성
- ChatRoom.status = ACTIVE
- ChatRoom.operationStatus = NORMAL
- actor를 ownerUserId로 설정
- owner의 ChatRoomMembership 생성
- owner의 ChatParticipationPeriod 생성
```

#### 관련 이벤트

```text
ROOM_CREATED
```

#### Idempotency

정의 예정

#### 실패 기준

정의 예정

---

### 5.2 closeRoom

#### 목적

오픈 채팅방을 종료한다.

#### Actor

room owner

#### 요청 필드

```text
roomId
```

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- actor가 room owner여야 한다.
```

#### 성공 시 상태 변화

```text
- ChatRoom.status = CLOSED
- ChatRoom.closedAt 기록
- 열려 있는 ChatParticipationPeriod 종료
```

#### 관련 이벤트

```text
ROOM_CLOSED
```

#### Idempotency

정의 예정

#### 실패 기준

정의 예정

---

### 5.3 transferOwner

#### 목적

방 owner를 다른 참여자에게 위임한다.

#### Actor

OPERATOR 또는 정책상 허용된 actor

#### 요청 필드

```text
roomId
targetUserId
```

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- targetUserId는 해당 room의 ACTIVE member여야 한다.
```

#### 성공 시 상태 변화

```text
- ChatRoom.ownerUserId 변경
- 필요한 경우 ChatRoom.operationStatus 갱신
```

#### 관련 이벤트

```text
ROOM_OWNER_TRANSFERRED
```

#### Idempotency

정의 예정

#### 실패 기준

정의 예정

---

## 6. 참여자 Commands

### 6.1 joinRoom

#### 목적

사용자가 오픈 채팅방에 참여한다.

#### Actor

인증된 USER

#### 요청 필드

```text
roomId
```

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- ChatRoom.operationStatus에서 참여가 허용되어야 한다.
```

#### 성공 시 상태 변화

```text
- ChatRoomMembership.status = ACTIVE
- 새로운 ChatParticipationPeriod 생성
- joinedSequence = ChatRoom.lastMessageSequence
```

#### 관련 이벤트

```text
ROOM_JOINED
```

#### Idempotency

정의 예정

#### 실패 기준

정의 예정

---

### 6.2 leaveRoom

#### 목적

사용자가 오픈 채팅방에서 나간다.

#### Actor

room owner가 아닌 ACTIVE member

#### 요청 필드

```text
roomId
```

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- actor의 ChatRoomMembership.status == ACTIVE
- actor는 room owner가 아니어야 한다.
```

#### 성공 시 상태 변화

```text
- ChatRoomMembership.status = LEFT
- 현재 열린 ChatParticipationPeriod 종료
- leftSequence = ChatRoom.lastMessageSequence
```

#### 관련 이벤트

```text
ROOM_LEFT
```

#### Idempotency

정의 예정

#### 실패 기준

정의 예정

---

## 7. 메시지 Commands

### 7.1 sendMessage

#### 목적

사용자가 참여 중인 room에 메시지를 생성한다.

#### Actor

ACTIVE member

#### 요청 필드

```text
roomId
clientMessageId
type
content
```

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- ChatRoom.operationStatus에서 메시지 전송이 허용되어야 한다.
- actor의 ChatRoomMembership.status == ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재해야 한다.
- clientMessageId가 있어야 한다.
- type과 content가 유효해야 한다.
```

#### 성공 시 상태 변화

```text
- ChatMessage 생성
- roomSequence 부여
- ChatRoom.lastMessageSequence 갱신
```

#### 관련 이벤트

```text
MESSAGE_SENT
```

#### Idempotency

```text
roomId + senderUserId + clientMessageId
```

동일 key로 이미 생성된 메시지가 있으면 새 메시지를 만들지 않고 기존 메시지를 반환한다.

동일 key에 다른 payload가 들어오면 충돌로 처리한다.

#### 실패 기준

정의 예정

---

### 7.2 editMessage

#### 목적

작성자가 자신이 보낸 메시지를 수정한다.

#### Actor

message sender

#### 요청 필드

```text
roomId
messageId
editVersion
content
```

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- message가 존재해야 한다.
- actor가 message sender여야 한다.
- ChatMessage.visibility == VISIBLE
- editVersion이 현재 메시지 버전과 일치해야 한다.
- content가 유효해야 한다.
```

#### 성공 시 상태 변화

```text
- ChatMessage.content 변경
- ChatMessage.editVersion 증가
- ChatMessage.editedAt 기록
```

#### 관련 이벤트

```text
MESSAGE_EDITED
```

#### Idempotency

정의 예정

#### 실패 기준

정의 예정

---

### 7.3 deleteMessage

#### 목적

작성자가 자신이 보낸 메시지를 삭제한다.

#### Actor

message sender

#### 요청 필드

```text
roomId
messageId
```

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- message가 존재해야 한다.
- actor가 message sender여야 한다.
```

#### 성공 시 상태 변화

```text
- ChatMessage.visibility = DELETED_BY_USER
- ChatMessage.deletedAt 기록
- 일반 사용자 응답에서 content 비노출
```

#### 관련 이벤트

```text
MESSAGE_DELETED_BY_USER
```

#### Idempotency

정의 예정

#### 실패 기준

정의 예정

---

## 8. 읽음 상태와 표시 Commands

### 8.1 markRead

#### 목적

사용자가 room에서 특정 sequence까지 읽었음을 기록한다.

#### Actor

ACTIVE member

#### 요청 필드

```text
roomId
lastReadSequence
```

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- actor의 ChatRoomMembership.status == ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재해야 한다.
- lastReadSequence가 현재 ChatParticipationPeriod 범위 안에 있어야 한다.
```

#### 성공 시 상태 변화

```text
- ChatReadCursor.lastReadSequence 갱신
- 기존 값보다 작은 sequence로는 갱신하지 않음
```

#### 관련 이벤트

```text
READ_CURSOR_UPDATED
READ_RECEIPT_UPDATED
```

#### Idempotency

정의 예정

#### 실패 기준

정의 예정

---

## 9. 실시간 연결 Commands

### 9.1 subscribeRoom

#### 목적

WebSocket connection이 room 이벤트를 수신하도록 구독한다.

#### Actor

인증된 connection

#### 요청 필드

```text
roomId
```

#### 사전 조건

```text
- connection의 인증 상태가 유효해야 한다.
- room이 존재해야 한다.
- room.status == ACTIVE
- actor의 ChatRoomMembership.status == ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재해야 한다.
```

#### 성공 시 상태 변화

```text
- WebSocketSession.subscribedRoomIds에 roomId 추가
```

#### 관련 이벤트

```text
없음
```

#### Idempotency

정의 예정

#### 실패 기준

정의 예정

---

### 9.2 unsubscribeRoom

#### 목적

WebSocket connection의 room 구독을 해제한다.

#### Actor

인증된 connection

#### 요청 필드

```text
roomId
```

#### 사전 조건

```text
- connection이 존재해야 한다.
```

#### 성공 시 상태 변화

```text
- WebSocketSession.subscribedRoomIds에서 roomId 제거
```

#### 관련 이벤트

```text
없음
```

#### Idempotency

정의 예정

#### 실패 기준

정의 예정

---

## 10. Query 분리 여부

조회 요청은 상태를 변경하지 않으므로 command와 분리할 수 있다.

다음 요청을 `commands.md`에 포함할지 별도 `queries.md`로 분리할지는 추후 결정한다.

```text
listRooms
getRoomDetail
listMessages
getUnreadCounts
getReadReceiptCount
catchUpMessages
```
