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
markRoomSystemManaged
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
activateReadSession
deactivateReadSession
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

ownerUserId는 request body에서 받지 않고 인증 token의 userId를 사용한다.

#### 요청 필드

```text
name
clientRequestId
```

#### 사전 조건

```text
- 인증 token이 유효해야 한다.
- name이 있어야 한다.
- name은 빈 문자열 또는 trim 후 빈 문자열이면 안 된다.
- name은 서비스 정책의 최대 길이 이하여야 한다.
- clientRequestId가 있어야 한다.
- actor가 서비스 정책상 room을 생성할 수 있어야 한다.
```

#### 성공 시 상태 변화

```text
- ChatRoom 생성
- ChatRoom.status = ACTIVE
- ChatRoom.operationStatus = NORMAL
- ChatRoom.ownerUserId = authenticatedUser.userId
- ChatRoom.lastMessageSequence = 0
- owner의 ChatRoomMembership 생성
- owner의 ChatRoomMembership.status = ACTIVE
- owner의 ChatParticipationPeriod 생성
- owner의 ChatParticipationPeriod.joinedSequence = 0
- owner의 ChatParticipationPeriod.leftSequence = null
- owner의 ChatReadCursor.lastReadSequence = 0
```

빈 room의 lastMessageSequence는 0이며, 첫 메시지의 roomSequence는 1부터 시작한다.

createRoom에서 owner 참여 상태를 만들 때도 joinRoom과 같은 participation 초기화 규칙을 사용한다.

위 상태 변화와 ROOM_CREATED 이벤트 발행 요청 기록은 같은 DB transaction에서 확정한다.

부분 성공은 허용하지 않는다.

createRoom은 ChatReadSession을 생성하지 않는다.

사용자가 생성된 room 화면을 실제로 보고 있음을 알리는 처리는 activateReadSession에서 수행한다.

#### 관련 이벤트

```text
ROOM_CREATED
```

createRoom 성공 시 ROOM_JOINED는 생성하지 않는다.

owner membership과 participation 생성은 ROOM_CREATED의 결과에 포함한다.

#### Idempotency

```text
ownerUserId + clientRequestId
```

동일 key로 이미 생성된 room이 있으면 새 room을 만들지 않고 기존 room을 반환한다.

동일 key에 같은 `name`이 들어오면 같은 요청으로 본다.

동일 key에 다른 `name`이 들어오면 충돌로 처리한다.

`roomId`, `createdAt`, `ownerUserId`는 서버 생성 또는 인증 기반 값이므로 idempotency payload 비교 기준에 포함하지 않는다.

#### 실패 기준

```text
- 인증 token이 유효하지 않다.
- actor가 서비스 정책상 room을 생성할 수 없다.
- name이 없다.
- name이 빈 문자열이거나 trim 후 빈 문자열이다.
- name이 서비스 정책의 최대 길이를 초과한다.
- clientRequestId가 없다.
- 같은 ownerUserId + clientRequestId로 이미 생성된 room이 있고, 기존 요청의 name과 현재 요청의 name이 다르다.
```

---

### 5.2 closeRoom

#### 목적

오픈 채팅방을 종료한다.

#### Actor

room owner 또는 OPERATOR

#### 요청 필드

```text
roomId
```

#### 사전 조건

```text
- room이 존재해야 한다.
- actor가 room owner 또는 OPERATOR여야 한다.
```

#### 성공 시 상태 변화

```text
- ChatRoom.status == ACTIVE이면 ChatRoom.status = CLOSED
- ChatRoom.closedAt 기록
- 열려 있는 모든 ChatParticipationPeriod 종료
- 각 ChatParticipationPeriod.leftSequence = ChatRoom.lastMessageSequence
- 해당 room의 모든 ChatReadSession.runtimeStatus = HIDDEN 또는 제거
- ChatRoom.status == CLOSED이면 성공 no-op
```

closeRoom은 room 생명주기를 종료하는 command다.

열려 있는 모든 ChatParticipationPeriod 종료가 확정되어야 room 종료가 완료된 것으로 본다.

leftSequence는 읽음 위치가 아니라 참여 구간 종료 시점을 기록하기 위한 값이다.

#### 관련 이벤트

```text
ROOM_CLOSED
```

ROOM_CLOSED는 ACTIVE에서 CLOSED로 전환된 경우에만 생성한다.

이미 CLOSED인 room에 대한 재호출에서는 이벤트를 생성하지 않는다.

#### Idempotency

별도 idempotency key는 사용하지 않는다.

권한 있는 actor가 이미 CLOSED인 room에 closeRoom을 호출하면 성공 no-op으로 처리한다.

#### 실패 기준

```text
- 인증 token이 유효하지 않다.
- room이 존재하지 않거나 actor에게 접근 가능하지 않다.
- actor가 room owner 또는 OPERATOR가 아니다.
```

---

### 5.3 transferOwner

#### 목적

방 owner를 다른 참여자에게 위임한다.

#### Actor

현재 room owner 또는 OPERATOR

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
- targetUserId != current ownerUserId
```

SYSTEM_MANAGED room에서는 OPERATOR가 transferOwner를 수행할 수 있다.

#### 성공 시 상태 변화

```text
- ChatRoom.ownerUserId = targetUserId
- ChatRoom.operationStatus = NORMAL
```

#### 관련 이벤트

```text
ROOM_OWNER_TRANSFERRED
```

ROOM_OWNER_TRANSFERRED는 ownerUserId가 실제 변경된 경우에만 생성한다.

#### Idempotency

별도 idempotency key는 사용하지 않는다.

targetUserId가 현재 owner이면 잘못된 요청으로 보고 실패 처리한다.

#### 실패 기준

```text
- 인증 token이 유효하지 않다.
- room이 존재하지 않거나 actor에게 접근 가능하지 않다.
- room.status != ACTIVE
- actor가 현재 room owner 또는 OPERATOR가 아니다.
- targetUserId가 없다.
- targetUserId가 해당 room의 ACTIVE member가 아니다.
- targetUserId가 현재 ownerUserId와 같다.
```

---

### 5.4 markRoomSystemManaged

#### 목적

owner 비활성화 등 시스템 판단으로 room을 운영자 조치 필요 상태로 전환한다.

#### Actor

SYSTEM

#### 요청 필드

```text
roomId
reason
```

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- owner가 owner 역할을 수행할 수 없는 상태여야 한다.
```

owner가 owner 역할을 수행할 수 없는 상태는 탈퇴, 정지, 삭제, 비활성화 등을 포함한다.

CLOSED room에는 markRoomSystemManaged를 적용하지 않는다.

#### 성공 시 상태 변화

```text
- ChatRoom.operationStatus == NORMAL이면 ChatRoom.operationStatus = SYSTEM_MANAGED
- ChatRoom.operationStatus == SYSTEM_MANAGED이면 성공 no-op
```

SYSTEM_MANAGED room은 기존 ACTIVE member의 메시지 전송과 읽음 처리는 기본 허용하고, 신규 참여는 기본 제한한다.

#### 관련 이벤트

```text
ROOM_SYSTEM_MANAGED
```

ROOM_SYSTEM_MANAGED는 NORMAL에서 SYSTEM_MANAGED로 전환된 경우에만 생성한다.

이미 SYSTEM_MANAGED인 room에 대한 재처리에서는 이벤트를 생성하지 않는다.

#### Idempotency

별도 idempotency key는 사용하지 않는다.

동일 owner 비활성화 이벤트가 반복 처리되어도 SYSTEM_MANAGED 상태이면 성공 no-op으로 처리한다.

#### 실패 기준

```text
- actor가 SYSTEM이 아니다.
- room이 존재하지 않는다.
- room.status != ACTIVE
- owner가 owner 역할을 수행할 수 없는 상태가 아니다.
```

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
- ChatRoom.operationStatus != SYSTEM_MANAGED
```

SYSTEM_MANAGED room은 기존 ACTIVE member의 메시지 전송과 읽음 처리는 기본 허용하지만, 신규 참여는 기본 제한한다.

단, 서비스 정책 또는 운영 판단에 따라 SYSTEM_MANAGED room의 신규 참여를 허용할 수 있다.

#### 성공 시 상태 변화

```text
- ChatRoomMembership이 없으면 새로 생성
- ChatRoomMembership.status = ACTIVE
- 새로운 ChatParticipationPeriod 생성
- joinedSequence = ChatRoom.lastMessageSequence
- ChatReadCursor.lastReadSequence = joinedSequence로 초기화 또는 보정
```

재입장 시 이전 ChatParticipationPeriod를 이어 쓰지 않고 새로운 ChatParticipationPeriod를 생성한다.

createRoom에서 owner 참여 상태를 만들 때도 joinRoom과 같은 participation 초기화 규칙을 사용한다.

#### 관련 이벤트

```text
ROOM_JOINED
```

ROOM_JOINED는 membership이 새로 ACTIVE가 되거나 LEFT에서 ACTIVE로 전환된 경우에만 생성한다.

이미 ACTIVE인 사용자의 재호출에서는 이벤트를 생성하지 않는다.

#### Idempotency

별도 idempotency key는 사용하지 않는다.

현재 ChatRoomMembership 상태를 기준으로 idempotent하게 처리한다.

```text
membership 없음
  -> ChatRoomMembership 생성
  -> ChatParticipationPeriod 생성
  -> ROOM_JOINED 생성

membership LEFT
  -> ChatRoomMembership.status = ACTIVE
  -> 새 ChatParticipationPeriod 생성
  -> ROOM_JOINED 생성

membership ACTIVE
  -> 성공 no-op
  -> 이벤트 생성 안 함
```

#### 실패 기준

```text
- 인증 token이 유효하지 않다.
- room이 존재하지 않거나 actor에게 접근 가능하지 않다.
- room.status != ACTIVE
- ChatRoom.operationStatus == SYSTEM_MANAGED이고 신규 참여가 제한되어 있다.
- actor가 서비스 정책상 참여할 수 없는 상태다.
```

---

### 6.2 leaveRoom

#### 목적

사용자가 오픈 채팅방에서 나간다.

#### Actor

room owner가 아닌 ACTIVE member

#### 요청 필드

```text
roomId
lastReadSequence optional
```

lastReadSequence가 제공되면 사용자가 방을 나가기 직전 실제로 확인한 마지막 메시지 sequence로 본다.

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- actor의 ChatRoomMembership이 존재해야 한다.
- actor는 room owner가 아니어야 한다.
- lastReadSequence가 제공된 경우 markRead와 같은 기준으로 검증한다.
```

SYSTEM_MANAGED room에서도 ACTIVE member의 leaveRoom은 허용한다.

room.status != ACTIVE이면 실패한다.

단, closeRoom 이후 지연된 leaveRoom 요청은 구현 정책에 따라 성공 no-op으로 처리할 수 있다.

#### 성공 시 상태 변화

```text
- ChatRoomMembership.status == ACTIVE이면 ChatRoomMembership.status = LEFT
- 현재 열린 ChatParticipationPeriod 종료
- leftSequence = ChatRoom.lastMessageSequence
- lastReadSequence가 제공되고 현재 cursor보다 크면 ChatReadCursor.lastReadSequence 갱신
- cursor가 실제로 증가한 경우에만 ChatReadCursor.readAt 갱신
- 해당 userId + roomId의 ChatReadSession.runtimeStatus = HIDDEN
- ChatRoomMembership.status == LEFT이면 성공 no-op
```

LEFT 상태에서는 일반 메시지 조회가 불가하지만, 참여 구간 종료 시점을 남기기 위해 leftSequence를 기록한다.

leaveRoom은 room 참여 상태를 변경하는 command이며, room 자체를 종료하지 않는다.

#### 관련 이벤트

```text
ROOM_LEFT
```

ROOM_LEFT는 ACTIVE에서 LEFT로 전환된 경우에만 생성한다.

이미 LEFT인 사용자의 재호출에서는 이벤트를 생성하지 않는다.

lastReadSequence로 ChatReadCursor가 실제 증가한 경우 READ_CURSOR_UPDATED, READ_RECEIPT_UPDATED를 생성할 수 있다.

#### Idempotency

별도 idempotency key는 사용하지 않는다.

현재 ChatRoomMembership 상태를 기준으로 idempotent하게 처리한다.

```text
membership ACTIVE
  -> ChatRoomMembership.status = LEFT
  -> ChatParticipationPeriod 종료
  -> ROOM_LEFT 생성

membership LEFT
  -> 성공 no-op
  -> 이벤트 생성 안 함
```

#### 실패 기준

```text
- 인증 token이 유효하지 않다.
- room이 존재하지 않거나 actor에게 접근 가능하지 않다.
- room.status != ACTIVE
- actor의 ChatRoomMembership이 존재하지 않는다.
- actor가 room owner다.
- actor의 ChatRoomMembership.status가 ACTIVE 또는 LEFT가 아니다.
- lastReadSequence가 제공된 경우 markRead 기준 검증에 실패한다.
```

---

## 7. 메시지 Commands

### 7.1 sendMessage

#### 목적

사용자가 참여 중인 room에 메시지를 생성한다.

#### Actor

ACTIVE member

`senderUserId`는 request body에서 받지 않고 인증 token의 userId를 사용한다.

owner도 ACTIVE member이면 일반 member와 동일하게 메시지를 전송할 수 있다.

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
- actor의 ChatRoomMembership.status == ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재해야 한다.
- clientMessageId가 있어야 한다.
- type == TEXT
- content는 빈 문자열 또는 trim 후 빈 문자열이면 안 된다.
- content는 서비스 정책의 최대 길이 이하여야 한다.
```

`ChatRoom.operationStatus == SYSTEM_MANAGED`라도 기본적으로 기존 ACTIVE member의 메시지 전송은 허용한다.

단, 서비스 정책 또는 운영 판단에 따라 SYSTEM_MANAGED room의 메시지 전송을 제한할 수 있다.

#### 성공 시 상태 변화

```text
- senderUserId = authenticatedUser.userId
- ChatMessage 생성
- ChatMessage.type = TEXT
- ChatMessage.visibility = VISIBLE
- ChatMessage.editVersion = 0
- ChatMessage.sentAt 기록
- roomSequence 부여
- ChatRoom.lastMessageSequence 갱신
- sender의 ChatReadCursor.lastReadSequence를 새 roomSequence까지 갱신
```

ChatMessage 생성과 ChatRoom.lastMessageSequence 갱신은 같은 DB transaction에서 확정한다.

sender가 보낸 메시지는 sender가 읽은 메시지로 본다.

다른 사용자의 VISIBLE ChatReadSession 기반 read cursor 자동 갱신은 sendMessage command의 상태 변화에 포함하지 않는다.

다른 사용자의 자동 읽음 처리는 메시지 broadcast/read-session 처리 단계에서 수행한다.

#### 관련 이벤트

```text
MESSAGE_SENT
```

MESSAGE_SENT는 영속 전달 보장 대상이다.

ChatMessage 생성과 MESSAGE_SENT 이벤트 발행 요청 기록은 같은 DB transaction에서 확정한다.

#### Idempotency

```text
roomId + senderUserId + clientMessageId
```

동일 key로 이미 생성된 메시지가 있으면 새 메시지를 만들지 않고 기존 메시지를 반환한다.

동일 key에 같은 `type + content`가 들어오면 같은 요청으로 본다.

동일 key에 다른 `type + content`가 들어오면 충돌로 처리한다.

`sentAt`, `messageId`, `roomSequence`는 서버 생성 값이므로 idempotency payload 비교 기준에 포함하지 않는다.

#### 실패 기준

```text
- 인증 token이 유효하지 않다.
- room이 존재하지 않거나 actor에게 접근 가능하지 않다.
- room.status != ACTIVE
- 서비스 정책 또는 운영 판단에 의해 현재 room의 메시지 전송이 제한되어 있다.
- actor의 ChatRoomMembership이 존재하지 않는다.
- actor의 ChatRoomMembership.status != ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재하지 않는다.
- clientMessageId가 없다.
- type이 TEXT가 아니다.
- content가 빈 문자열이거나 trim 후 빈 문자열이다.
- content가 서비스 정책의 최대 길이를 초과한다.
- 같은 roomId + senderUserId + clientMessageId로 이미 생성된 메시지가 있고, 기존 메시지의 type + content와 현재 요청의 type + content가 다르다.
```

---

### 7.2 editMessage

#### 목적

작성자가 VISIBLE 상태의 TEXT 메시지 content를 수정한다.

#### Actor

message sender

OPERATOR도 메시지 content를 수정할 수 없다.

작성자라도 현재 room의 ACTIVE member여야 수정할 수 있다.

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
- message.roomId == roomId
- actor가 message sender여야 한다.
- actor의 ChatRoomMembership.status == ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재해야 한다.
- message.roomSequence가 현재 ChatParticipationPeriod 범위 안에 있어야 한다.
- ChatMessage.type == TEXT
- ChatMessage.visibility == VISIBLE
- editVersion이 있어야 한다.
- editVersion이 현재 메시지 버전과 일치해야 한다.
- content는 빈 문자열 또는 trim 후 빈 문자열이면 안 된다.
- content는 서비스 정책의 최대 길이 이하여야 한다.
```

`ChatRoom.operationStatus == SYSTEM_MANAGED`라도 기본적으로 ACTIVE member의 editMessage는 허용한다.

단, 서비스 정책 또는 운영 판단에 따라 SYSTEM_MANAGED room의 메시지 수정을 제한할 수 있다.

editVersion은 클라이언트가 기준으로 삼은 메시지 버전이며, optimistic locking에 사용한다.

#### 성공 시 상태 변화

```text
- 새 content가 기존 content와 다르면 ChatMessage.content 변경
- content가 실제 변경된 경우 ChatMessage.editVersion 증가
- content가 실제 변경된 경우 ChatMessage.editedAt 기록
- 새 content가 기존 content와 같으면 성공 no-op
```

editMessage는 ChatReadCursor를 변경하지 않는다.

#### 관련 이벤트

```text
MESSAGE_EDITED
```

MESSAGE_EDITED는 content가 실제 변경된 경우에만 생성한다.

MESSAGE_EDITED는 영속 전달 보장 대상이다.

ChatMessage 수정과 MESSAGE_EDITED 이벤트 발행 요청 기록은 같은 DB transaction에서 확정한다.

#### Idempotency

별도 idempotency key는 사용하지 않는다.

editVersion이 현재 메시지 버전과 일치할 때만 수정한다.

수정 성공 시 editVersion을 증가시킨다.

editVersion이 일치하지 않으면 충돌로 처리한다.

같은 content로 수정 요청이 들어오면 성공 no-op으로 처리하고 editVersion을 증가시키지 않는다.

#### 실패 기준

```text
- 인증 token이 유효하지 않다.
- room이 존재하지 않거나 actor에게 접근 가능하지 않다.
- room.status != ACTIVE
- 서비스 정책 또는 운영 판단에 의해 현재 room의 메시지 수정이 제한되어 있다.
- message가 존재하지 않는다.
- message.roomId != roomId
- actor가 message sender가 아니다.
- actor의 ChatRoomMembership이 존재하지 않는다.
- actor의 ChatRoomMembership.status != ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재하지 않는다.
- message.roomSequence가 현재 ChatParticipationPeriod 범위 밖이다.
- ChatMessage.type != TEXT
- ChatMessage.visibility != VISIBLE
- editVersion이 없다.
- editVersion이 현재 메시지 버전과 일치하지 않는다.
- content가 빈 문자열이거나 trim 후 빈 문자열이다.
- content가 서비스 정책의 최대 길이를 초과한다.
- 서비스 정책상 수정 가능 조건을 만족하지 않는다.
```

---

### 7.3 deleteMessage

#### 목적

작성자가 VISIBLE 상태의 메시지를 삭제 표시 상태로 전환한다.

#### Actor

message sender

OPERATOR의 운영 삭제 또는 숨김 처리는 현재 core deleteMessage 범위에 포함하지 않으며, 향후 moderateMessage로 분리한다.

작성자라도 현재 room의 ACTIVE member여야 삭제할 수 있다.

#### 요청 필드

```text
roomId
messageId
editVersion
```

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- message가 존재해야 한다.
- message.roomId == roomId
- actor가 message sender여야 한다.
- actor의 ChatRoomMembership.status == ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재해야 한다.
- message.roomSequence가 현재 ChatParticipationPeriod 범위 안에 있어야 한다.
- editVersion이 있어야 한다.
- editVersion이 현재 메시지 버전과 일치해야 한다.
```

`ChatRoom.operationStatus == SYSTEM_MANAGED`라도 기본적으로 ACTIVE member의 deleteMessage는 허용한다.

단, 서비스 정책 또는 운영 판단에 따라 SYSTEM_MANAGED room의 메시지 삭제를 제한할 수 있다.

#### 성공 시 상태 변화

```text
- ChatMessage.visibility == VISIBLE이면 ChatMessage.visibility = DELETED_BY_USER
- visibility가 실제 변경된 경우 ChatMessage.editVersion 증가
- visibility가 실제 변경된 경우 ChatMessage.deletedAt 기록
- 일반 사용자 응답에서 content 비노출
- ChatMessage.visibility == DELETED_BY_USER이면 성공 no-op
```

deleteMessage는 물리 삭제하지 않는다.

deleteMessage는 ChatReadCursor를 변경하지 않는다.

#### 관련 이벤트

```text
MESSAGE_DELETED_BY_USER
```

MESSAGE_DELETED_BY_USER는 visibility가 실제 변경된 경우에만 생성한다.

MESSAGE_DELETED_BY_USER는 영속 전달 보장 대상이다.

ChatMessage visibility 변경과 MESSAGE_DELETED_BY_USER 이벤트 발행 요청 기록은 같은 DB transaction에서 확정한다.

#### Idempotency

별도 idempotency key는 사용하지 않는다.

editVersion이 현재 메시지 버전과 일치할 때만 삭제한다.

삭제 성공 시 editVersion을 증가시킨다.

editVersion이 일치하지 않으면 충돌로 처리한다.

이미 DELETED_BY_USER인 메시지에 대한 deleteMessage 재호출은 성공 no-op으로 처리하고 editVersion을 증가시키지 않는다.

#### 실패 기준

```text
- 인증 token이 유효하지 않다.
- room이 존재하지 않거나 actor에게 접근 가능하지 않다.
- room.status != ACTIVE
- 서비스 정책 또는 운영 판단에 의해 현재 room의 메시지 삭제가 제한되어 있다.
- message가 존재하지 않는다.
- message.roomId != roomId
- actor가 message sender가 아니다.
- actor의 ChatRoomMembership이 존재하지 않는다.
- actor의 ChatRoomMembership.status != ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재하지 않는다.
- message.roomSequence가 현재 ChatParticipationPeriod 범위 밖이다.
- editVersion이 없다.
- editVersion이 현재 메시지 버전과 일치하지 않는다.
- 서비스 정책상 삭제 가능 조건을 만족하지 않는다.
```

---

## 8. 읽음 상태와 표시 Commands

### 8.1 markRead

#### 목적

클라이언트가 사용자가 실제로 확인한 마지막 메시지 sequence를 서버에 알려 read cursor를 보정한다.

#### Actor

ACTIVE member

#### 요청 필드

```text
roomId
lastReadSequence
```

`lastReadSequence`는 클라이언트가 사용자가 실제로 확인한 마지막 visible message sequence로 보낸 값이다.

서버는 이 값을 검증한 뒤 `ChatReadCursor.lastReadSequence`에 반영한다.

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- actor의 ChatRoomMembership.status == ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재해야 한다.
- lastReadSequence가 있어야 한다.
- lastReadSequence는 숫자여야 한다.
- lastReadSequence >= currentChatParticipationPeriod.joinedSequence
- lastReadSequence <= ChatRoom.lastMessageSequence
```

`ChatRoom.operationStatus == SYSTEM_MANAGED`라도 기본적으로 ACTIVE member의 markRead는 허용한다.

단, 서비스 정책 또는 운영 판단에 따라 SYSTEM_MANAGED room의 읽음 처리를 제한할 수 있다.

#### 성공 시 상태 변화

```text
- lastReadSequence > current ChatReadCursor.lastReadSequence이면 ChatReadCursor.lastReadSequence 갱신
- cursor가 실제로 증가한 경우에만 ChatReadCursor.readAt 갱신
- lastReadSequence <= current ChatReadCursor.lastReadSequence이면 성공 no-op
```

`ChatReadCursor.lastReadSequence`는 감소하지 않는다.

#### 관련 이벤트

```text
READ_CURSOR_UPDATED
READ_RECEIPT_UPDATED
```

cursor가 실제로 증가한 경우에만 읽음 관련 이벤트를 생성할 수 있다.

READ_CURSOR_UPDATED는 같은 user의 다른 connection 또는 요청 client의 cursor 동기화에 사용한다.

READ_RECEIPT_UPDATED는 room 구독자의 메시지별 read receipt count 갱신에 사용한다.

두 이벤트는 실시간 표시 보조 이벤트이며, 영속 전달 보장 대상은 아니다.

#### Idempotency

별도 idempotency key는 사용하지 않는다.

`ChatReadCursor.lastReadSequence`가 감소하지 않는 monotonic update이므로 같은 요청을 반복해도 같은 결과가 된다.

현재 cursor보다 작거나 같은 sequence는 성공 no-op으로 처리한다.

#### 실패 기준

```text
- 인증 token이 유효하지 않다.
- room이 존재하지 않거나 actor에게 접근 가능하지 않다.
- room.status != ACTIVE
- 서비스 정책 또는 운영 판단에 의해 현재 room의 읽음 처리가 제한되어 있다.
- actor의 ChatRoomMembership이 존재하지 않는다.
- actor의 ChatRoomMembership.status != ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재하지 않는다.
- lastReadSequence가 없다.
- lastReadSequence가 숫자가 아니다.
- lastReadSequence < currentChatParticipationPeriod.joinedSequence
- lastReadSequence > ChatRoom.lastMessageSequence
```

---

### 8.2 activateReadSession

#### 목적

현재 WebSocket connection에서 사용자가 특정 room 화면을 보고 있음을 서버에 알린다.

activateReadSession은 WebSocket connection context에서 처리하는 runtime command다.

#### Actor

ACTIVE member

#### 요청 필드

```text
roomId
lastReadSequence optional
```

connectionId는 request field로 받지 않고 현재 WebSocket connection context에서 얻는다.

lastReadSequence가 제공되면 클라이언트가 room 화면 진입 시 실제로 확인한 마지막 메시지 sequence로 본다.

#### 사전 조건

```text
- connection의 인증 상태가 유효해야 한다.
- room이 존재해야 한다.
- room.status == ACTIVE
- actor의 ChatRoomMembership.status == ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재해야 한다.
- lastReadSequence가 제공된 경우 숫자여야 한다.
- lastReadSequence가 제공된 경우 lastReadSequence >= currentChatParticipationPeriod.joinedSequence
- lastReadSequence가 제공된 경우 lastReadSequence <= ChatRoom.lastMessageSequence
```

`ChatRoom.operationStatus == SYSTEM_MANAGED`라도 기본적으로 ACTIVE member의 activateReadSession은 허용한다.

단, 서비스 정책 또는 운영 판단에 따라 SYSTEM_MANAGED room의 읽음 처리를 제한할 수 있다.

#### 성공 시 상태 변화

```text
- 현재 connectionId + userId + roomId 기준 ChatReadSession 생성 또는 갱신
- ChatReadSession.runtimeStatus = VISIBLE
- lastReadSequence가 제공되고 현재 cursor보다 크면 ChatReadCursor.lastReadSequence 갱신
- cursor가 실제로 증가한 경우에만 ChatReadCursor.readAt 갱신
```

이미 VISIBLE 상태이면 성공 no-op으로 처리한다.

#### 관련 이벤트

```text
없음
```

ChatReadSession만 VISIBLE로 변경된 경우 관련 이벤트는 생성하지 않는다.

lastReadSequence로 ChatReadCursor가 실제 증가한 경우 READ_CURSOR_UPDATED, READ_RECEIPT_UPDATED를 생성할 수 있다.

#### Idempotency

별도 idempotency key는 사용하지 않는다.

이미 VISIBLE이면 성공 no-op으로 처리한다.

lastReadSequence가 현재 cursor보다 작거나 같은 경우 cursor 갱신은 성공 no-op으로 처리한다.

#### 실패 기준

```text
- connection 인증 상태가 유효하지 않다.
- room이 존재하지 않거나 actor에게 접근 가능하지 않다.
- room.status != ACTIVE
- 서비스 정책 또는 운영 판단에 의해 현재 room의 읽음 처리가 제한되어 있다.
- actor의 ChatRoomMembership이 존재하지 않는다.
- actor의 ChatRoomMembership.status != ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재하지 않는다.
- lastReadSequence가 제공된 경우 숫자가 아니다.
- lastReadSequence가 제공된 경우 lastReadSequence < currentChatParticipationPeriod.joinedSequence
- lastReadSequence가 제공된 경우 lastReadSequence > ChatRoom.lastMessageSequence
```

---

### 8.3 deactivateReadSession

#### 목적

현재 WebSocket connection에서 사용자가 특정 room 화면을 더 이상 보고 있지 않음을 서버에 알린다.

deactivateReadSession은 자동 읽음 처리 대상에서 제외하기 위한 runtime command이며, room 참여 상태를 변경하지 않는다.

#### Actor

인증된 WebSocket connection

#### 요청 필드

```text
roomId
lastReadSequence optional
```

connectionId는 request field로 받지 않고 현재 WebSocket connection context에서 얻는다.

lastReadSequence가 제공되면 클라이언트가 room 화면을 떠나기 직전 실제로 확인한 마지막 메시지 sequence로 본다.

#### 사전 조건

```text
- connection이 존재해야 한다.
- connection의 인증 상태가 유효해야 한다.
- roomId가 있어야 한다.
```

lastReadSequence가 제공되지 않은 경우, room 또는 membership 상태가 이미 변경되었더라도 성공 no-op으로 처리할 수 있다.

lastReadSequence가 제공된 경우에는 markRead와 같은 기준으로 검증한다.

#### 성공 시 상태 변화

```text
- 현재 connectionId + userId + roomId 기준 ChatReadSession이 있으면 ChatReadSession.runtimeStatus = HIDDEN
- ChatReadSession이 없거나 이미 HIDDEN이면 성공 no-op
- lastReadSequence가 제공되고 현재 cursor보다 크면 ChatReadCursor.lastReadSequence 갱신
- cursor가 실제로 증가한 경우에만 ChatReadCursor.readAt 갱신
```

#### 관련 이벤트

```text
없음
```

ChatReadSession만 HIDDEN으로 변경된 경우 관련 이벤트는 생성하지 않는다.

lastReadSequence로 ChatReadCursor가 실제 증가한 경우 READ_CURSOR_UPDATED, READ_RECEIPT_UPDATED를 생성할 수 있다.

#### Idempotency

별도 idempotency key는 사용하지 않는다.

ChatReadSession이 없거나 이미 HIDDEN이면 성공 no-op으로 처리한다.

lastReadSequence가 현재 cursor보다 작거나 같은 경우 cursor 갱신은 성공 no-op으로 처리한다.

#### 실패 기준

```text
- connection이 존재하지 않는다.
- connection 인증 상태가 유효하지 않다.
- roomId가 없다.
- lastReadSequence가 제공된 경우 markRead 기준 검증에 실패한다.
```

---

## 9. 실시간 연결 Commands

### 9.1 subscribeRoom

#### 목적

현재 WebSocket connection이 특정 room의 실시간 이벤트를 수신할 수 있도록 구독 상태를 등록한다.

subscribeRoom은 WebSocket connection context에서 처리하는 runtime command다.

#### Actor

인증된 WebSocket connection의 ACTIVE member

#### 요청 필드

```text
roomId
```

connectionId는 request field로 받지 않고 현재 WebSocket connection context에서 얻는다.

#### 사전 조건

```text
- connection의 인증 상태가 유효해야 한다.
- room이 존재해야 한다.
- room.status == ACTIVE
- actor의 ChatRoomMembership.status == ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재해야 한다.
```

`ChatRoom.operationStatus == SYSTEM_MANAGED`라도 기본적으로 ACTIVE member의 subscribeRoom은 허용한다.

단, 서비스 정책 또는 운영 판단에 따라 SYSTEM_MANAGED room의 구독을 제한할 수 있다.

CLOSED room은 subscribeRoom을 허용하지 않는다.

#### 성공 시 상태 변화

```text
- WebSocketSession.subscribedRoomIds에 roomId 추가
```

room 구독 여부는 WebSocketSession.subscribedRoomIds로 판단한다.

WebSocketSession.runtimeStatus는 connection 생명주기 상태를 나타낸다.

subscribeRoom은 ChatReadSession을 VISIBLE로 만들지 않는다.

클라이언트가 room 화면을 보고 있는 경우 subscribeRoom 성공 후 activateReadSession을 호출해야 한다.

#### 관련 이벤트

```text
없음
```

#### Idempotency

별도 idempotency key는 사용하지 않는다.

이미 같은 roomId가 subscribedRoomIds에 있으면 성공 no-op으로 처리한다.

#### 실패 기준

```text
- connection 인증 상태가 유효하지 않다.
- room이 존재하지 않거나 actor에게 접근 가능하지 않다.
- room.status != ACTIVE
- 서비스 정책 또는 운영 판단에 의해 현재 room의 구독이 제한되어 있다.
- actor의 ChatRoomMembership이 존재하지 않는다.
- actor의 ChatRoomMembership.status != ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재하지 않는다.
```

---

### 9.2 unsubscribeRoom

#### 목적

현재 WebSocket connection이 특정 room 이벤트를 더 이상 수신하지 않도록 구독 상태를 해제한다.

unsubscribeRoom은 구독 및 read session cleanup 성격의 runtime command다.

#### Actor

인증된 WebSocket connection

#### 요청 필드

```text
roomId
```

connectionId는 request field로 받지 않고 현재 WebSocket connection context에서 얻는다.

#### 사전 조건

```text
- connection이 존재해야 한다.
- roomId가 있어야 한다.
```

room 또는 membership 상태가 이미 변경되었더라도 성공 no-op으로 처리할 수 있다.

#### 성공 시 상태 변화

```text
- WebSocketSession.subscribedRoomIds에서 roomId 제거
- 해당 connectionId + roomId의 ChatReadSession.runtimeStatus = HIDDEN 또는 제거
```

이미 구독 중이 아니면 성공 no-op으로 처리한다.

unsubscribeRoom은 ChatReadCursor를 변경하지 않는다.

마지막 읽음 보정은 deactivateReadSession 또는 markRead로 처리한다.

클라이언트는 room 화면을 떠날 때 deactivateReadSession을 먼저 호출한 뒤 unsubscribeRoom을 호출하는 것을 권장한다.

#### 관련 이벤트

```text
없음
```

#### Idempotency

별도 idempotency key는 사용하지 않는다.

이미 구독 중이 아니면 성공 no-op으로 처리한다.

#### 실패 기준

```text
- connection이 존재하지 않는다.
- roomId가 없다.
```

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
