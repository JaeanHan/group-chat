# 오픈 채팅방 클라이언트 Domain View

## 1. 목적

이 문서는 오픈 채팅방 Core 원칙을 구현하기 위해 클라이언트가 비즈니스 핵심 요소를 어떤 프론트엔드 도메인 모델로 해석하는지 정의한다.

프론트엔드 도메인 모델은 서버에서 확정된 도메인 상태를 대체하지 않는다.

클라이언트는 서버에서 전달된 외부 상태를 사용자 맥락에 맞게 해석해 프론트엔드 도메인 모델을 만들고, 프론트엔드 도메인 모델을 다시 capability와 interaction으로 변환한다.

Domain View의 핵심 공식은 다음과 같다.

```text
[외부 상태]
        \
         -> [프론트엔드 도메인 모델] -> [Capability] -> [Interaction]
        /
[사용자 맥락]
```

이 문서는 loading spinner, toast 문구, modal layout 같은 UI 표현 자체를 정의하지 않는다.

다만 loading, disabled, retry, stale, unavailable 같은 UI 표현을 발생시키는 프론트엔드 도메인 모델과 capability는 정의한다.

---

## 2. 도메인 경계

### 2.1 외부 상태

외부 상태는 클라이언트 밖에서 확정되어 전달된 도메인 상태다.

이 문서에서 외부 상태는 다음을 의미한다.

```text
- query result
- command response
- server event
```

클라이언트는 외부 상태를 임의로 source of truth처럼 바꾸지 않는다.

### 2.2 사용자 맥락

사용자 맥락은 클라이언트가 관찰할 수 있는 사용 상황이다.

```text
- 현재 페이지
- 입력 중인 값
- 스크롤 위치
- foreground/background 여부
- 진행 중인 사용자 행동
- 실시간 연결 상태
```

같은 외부 상태라도 사용자 맥락이 다르면 다른 프론트엔드 도메인 모델로 해석될 수 있다.

### 2.3 프론트엔드 도메인 모델

프론트엔드 도메인 모델은 외부 상태와 사용자 맥락을 조합해 사용자 흐름으로 해석한 상태다.

프론트엔드 도메인 모델은 서버 source of truth가 아니라, 화면에서 비즈니스 요소를 다루기 위한 클라이언트 모델이다.

각 비즈니스 요소의 프론트엔드 도메인 모델은 다음을 포함할 수 있다.

```text
- 서버에서 확인한 상태를 화면 맥락에 맞게 해석한 모델
- 서버 확정 전 pending 모델
- 실시간 연결/구독/read session 같은 runtime 모델
- 메시지 병합/읽음 표시 같은 파생 모델
```

이 문서에서 정의하는 프론트엔드 도메인 모델은 다음과 같다.

```text
채팅방:
  ClientRoomSummary
  ClientRoomDetail

참여:
  ClientParticipation
  PendingParticipationAction
  ClientMessageAccessScope

메시지:
  PendingMessage
  ClientMessage

읽음과 표시:
  ClientReadState
  ClientReadCursorSnapshot
  ClientMessageReadReceipt

실시간 연결:
  ClientRealtimeConnection
  ClientRoomSubscription
  ClientReadSession
```

### 2.4 Capability

Capability는 프론트엔드 도메인 모델을 바탕으로 현재 사용자가 어떤 행동을 할 수 있는지 판단한 결과다.

Capability는 최종 권한 판단이 아니다.

최종 권한 판단은 서버 command 결과를 따른다.

클라이언트 capability는 사용자가 명백히 불가능한 행동을 시도하지 않도록 화면 행동을 제한하거나 유도하기 위한 사전 판단이다.

이 문서에서 정의하는 capability는 다음과 같다.

```text
ClientRoomCapability
ClientParticipationCapability
ClientMessageCapability
ClientReadCapability
ClientRealtimeConnectionCapability
ClientRoomRealtimeCapability
```

### 2.5 Interaction

Interaction은 capability를 바탕으로 화면에서 허용, 차단, 유도되는 사용자 행동이다.

```text
허용:
  현재 상태에서 실행할 수 있는 행동

차단:
  현재 상태에서 실행하면 안 되는 행동

유도:
  사용자가 다음 흐름으로 이동하도록 안내할 행동
```

---

## 3. 채팅방

채팅방은 사용자가 발견하고, 상세를 확인하고, 참여 여부를 결정하는 대상이다.

클라이언트에서 채팅방은 메시지 목록을 담는 container가 아니라, 사용자가 대화에 접근하기 전 맥락을 이해하고 참여 의사를 표현하는 진입점이다.

### 3.1 외부 상태

```text
RoomSummary:
  roomId
  name
  operationStatus
  participantCount
  lastMessagePreview nullable
  unreadCount

RoomDetail:
  roomId
  name
  operationStatus
  owner
  participantCount
  currentUserParticipationStatus

room related server event:
  ROOM_CREATED
  ROOM_CLOSED
  ROOM_SYSTEM_MANAGED
  ROOM_OWNER_TRANSFERRED
```

### 3.2 사용자 맥락

```text
사용자가 내가 참여한 방 목록을 보고 있음
사용자가 참여 가능한 방 목록을 보고 있음
사용자가 room 상세를 보고 있음
사용자가 room 참여를 시도함
사용자가 stale한 room 화면에 머무름
```

### 3.3 프론트엔드 도메인 모델

`ClientRoomSummary`는 사용자가 목록에서 room을 발견하고 현재 대화 흐름을 빠르게 파악하기 위한 목록 모델이다.

```text
ClientRoomSummary:
  roomId
  name
  operationStatus
  participantCount
  lastMessagePreview nullable
  unreadCount
```

`lastMessagePreview`는 메시지가 없거나 참여 전 room인 경우 없을 수 있다.

`unreadCount`는 서버 `RoomSummary`의 room unread count를 그대로 반영한다.

참여 가능한 room 목록에서 unread 의미가 없는 경우에도 클라이언트가 임의 계산하지 않고 서버가 제공한 값을 따른다.

`ClientRoomDetail`은 사용자가 room 정보를 보고 참여 여부를 판단하거나 채팅 화면 진입 가능 여부를 판단하기 위한 상세 모델이다.

```text
ClientRoomDetail:
  roomId
  name
  owner
  operationStatus
  participantCount
  participationStatus
```

`participationStatus`는 현재 사용자 기준 참여 상태다.

`operationStatus`는 room을 즉시 사용 불가로 해석하기 위한 값이 아니라, 운영 개입 여부를 판단하기 위한 값이다.

### 3.4 모델 형성 및 보정

모델 식별:

```text
RoomSummary.roomId:
  같은 ClientRoomSummary로 본다.

RoomDetail.roomId:
  같은 ClientRoomDetail로 본다.
```

모델 형성:

```text
RoomSummary + 사용자가 room 목록을 보고 있음
  -> ClientRoomSummary

RoomDetail + 사용자가 room 상세 또는 채팅 화면을 보고 있음
  -> ClientRoomDetail
```

모델 보정:

```text
listRooms:
  ClientRoomSummary를 roomId 기준으로 보정한다.

getRoomDetail:
  ClientRoomDetail을 roomId 기준으로 보정한다.
  currentUserParticipationStatus는 ClientParticipation 보정 입력으로 사용한다.

getUnreadCounts:
  같은 roomId의 ClientRoomSummary.unreadCount를 서버 값으로 덮어쓴다.

room related server event:
  이미 보관 중인 ClientRoomSummary 또는 ClientRoomDetail이 있으면 roomId 기준으로 보정한다.
```

목록 조회 결과에 특정 room이 없다는 사실만으로 이미 열려 있는 `ClientRoomDetail`을 즉시 제거하지 않는다.

room unread count는 `ClientRoomSummary.unreadCount`에 반영하며, 클라이언트가 직접 계산하지 않는다.

### 3.5 Capability

`ClientRoomCapability`는 현재 사용자 맥락에서 room에 대해 어떤 행동을 할 수 있는지 나타낸다.

```text
ClientRoomCapability:
  roomId
  canOpenDetail
  canJoin
  canEnterChat
  canSendMessage
  canSubscribe
```

Capability 판단 입력:

```text
- ClientRoomSummary
- ClientRoomDetail
- ClientParticipation
- PendingParticipationAction
- ClientRealtimeConnection
- 현재 페이지 맥락
```

판단 대상:

```text
canOpenDetail:
  사용자가 room 정보를 보고 참여 여부를 판단할 수 있는가

canJoin:
  사용자가 room에 참여할 수 있는가

canEnterChat:
  사용자가 room에서 메시지 조회/전송 흐름에 들어갈 수 있는가

canSendMessage:
  사용자가 room에서 메시지를 보낼 수 있는가

canSubscribe:
  현재 connection이 room event를 구독할 수 있는가
```

방 목록에 보인다는 것은 채팅 화면을 사용할 수 있다는 뜻이 아니다.

방 상세를 볼 수 있다는 것도 현재 사용자가 참여 중이라는 뜻이 아니다.

운영 개입 중인 room은 종료된 room과 다르게 해석한다.

운영 개입 중이어도 기존 참여자의 메시지 사용이 가능할 수 있다.

### 3.6 Interaction

```text
허용:
  room 상세 열기
  joinRoom 요청
  채팅 화면 진입
  sendMessage 요청

차단:
  CLOSED room 상세 사용
  참여하지 않은 room의 메시지 입력
  사용할 수 없는 room의 stale action

유도:
  참여 전이면 참여 행동을 유도한다.
  종료된 room이면 사용할 수 없음을 안내한다.
```

---

## 4. 참여

참여는 현재 사용자가 특정 room에서 채팅 화면을 사용할 수 있는지 결정하는 기준이다.

클라이언트는 참여를 전체 참여자 목록이 아니라 현재 사용자와 특정 room 사이의 관계로 해석한다.

참여는 단순한 권한 값이 아니라 메시지 조회 범위와 채팅 화면 사용 가능 여부를 함께 제한한다.

### 4.1 외부 상태

```text
RoomDetail.currentUserParticipationStatus:
  JOINED
  NOT_JOINED
  LEFT
  UNAVAILABLE

ROOM_JOINED event
ROOM_LEFT event
joinRoom command response
leaveRoom command response
```

### 4.2 사용자 맥락

```text
사용자가 room 상세를 보고 있음
사용자가 채팅 화면에 진입하려고 함
사용자가 room에서 나가려고 함
사용자가 나간 room에 다시 접근함
joinRoom 요청이 진행 중임
leaveRoom 요청이 진행 중임
```

### 4.3 프론트엔드 도메인 모델

`ClientParticipation`은 현재 사용자와 특정 room 사이의 참여 관계를 나타낸다.

```text
ClientParticipation:
  roomId
  status
```

`status`는 현재 사용자가 해당 room에서 메시지 화면을 사용할 수 있는지 판단하는 기준이다.

`PendingParticipationAction`은 joinRoom 또는 leaveRoom 요청이 서버에서 확정되기 전까지 사용자의 진행 중 행동을 표현한다.

```text
PendingParticipationAction:
  roomId
  action
  status
  requestedAt
  failedReason optional

PendingParticipationAction.action:
  JOIN
  LEAVE

PendingParticipationAction.status:
  PENDING
  FAILED
```

`failedReason`은 실패 안내나 재시도 판단에 필요한 경우에만 가진다.

`ClientMessageAccessScope`는 현재 사용자가 어떤 메시지 범위를 볼 수 있는지 나타낸다.

```text
ClientMessageAccessScope:
  roomId
  currentParticipationOnly
  canContinuePreviousMessages
```

재입장 후에는 이전 참여 구간 메시지를 현재 메시지로 이어 보지 않는다.

### 4.4 모델 형성 및 보정

모델 식별:

```text
ClientParticipation:
  roomId 기준으로 현재 사용자에 대한 참여 상태만 해석한다.

PendingParticipationAction:
  roomId + action 기준으로 같은 pending action으로 본다.

ClientMessageAccessScope:
  roomId 기준으로 메시지 조회 범위를 판단한다.
```

모델 형성:

```text
RoomDetail.currentUserParticipationStatus + 현재 사용자 맥락
  -> ClientParticipation

joinRoom/leaveRoom 사용자 행동
  -> PendingParticipationAction

ClientParticipation + RoomDetail.currentUserParticipationStatus
  -> ClientMessageAccessScope
```

모델 보정:

```text
joinRoom response:
  성공하면 PendingParticipationAction(JOIN)을 제거하고 ClientParticipation을 JOINED로 보정한다.
  실패하면 PendingParticipationAction(JOIN)을 FAILED로 보정한다.

leaveRoom response:
  성공하면 PendingParticipationAction(LEAVE)을 제거하고 ClientParticipation을 LEFT로 보정한다.
  실패하면 PendingParticipationAction(LEAVE)을 FAILED로 보정한다.

getRoomDetail:
  currentUserParticipationStatus를 기준으로 ClientParticipation을 보정한다.

ROOM_JOINED / ROOM_LEFT:
  현재 사용자와 관련된 event이면 ClientParticipation 보정 입력으로 사용한다.
```

stale query 가능성이 있는 경우 pending 상태를 query 결과 하나만으로 즉시 실패 확정하지 않는다.

명확한 command response 또는 현재 상태를 확정할 수 있는 query 결과를 기준으로 pending 상태를 정리한다.

클라이언트는 다른 사용자의 참여 상태를 기본 모델로 보관하지 않는다.

### 4.5 Capability

`ClientParticipationCapability`와 `ClientMessageAccessScope`는 참여 상태가 현재 사용자의 행동에 어떤 영향을 주는지 판단한다.

```text
ClientParticipationCapability:
  roomId
  canReadMessages
  canWriteMessages
  canSubscribeRoom
  canLeaveRoom
```

Capability 판단 입력:

```text
- ClientParticipation
- ClientRoomDetail
- PendingParticipationAction
- ClientRoomCapability
- 현재 페이지 맥락
```

판단 대상:

```text
canReadMessages:
  현재 사용자가 room 메시지를 조회할 수 있는가

canWriteMessages:
  현재 사용자가 room에 메시지를 보낼 수 있는가

canSubscribeRoom:
  현재 사용자가 room event를 구독할 수 있는가

canLeaveRoom:
  현재 사용자가 room에서 나갈 수 있는가
```

재입장 후 메시지 조회 범위는 서버가 확정한 현재 참여 구간을 따른다.

`ClientMessageAccessScope`는 나간 room을 어떻게 표시할지와 분리해서, 메시지 조회 범위 자체를 설명한다.

### 4.6 Interaction

```text
허용:
  joinRoom 요청
  leaveRoom 요청
  채팅 화면 진입

차단:
  참여하지 않은 room의 sendMessage
  LEFT 상태 room의 메시지 조회 유지
  이전 참여 구간 메시지 이어 보기

유도:
  참여 전이면 참여 버튼을 통해 joinRoom을 유도한다.
  나간 room이면 채팅 화면을 닫거나 재입장 흐름을 유도한다.
```

---

## 5. 메시지

메시지는 서버에서 확인한 메시지와 서버 확정 전 임시 메시지로 나뉜다.

클라이언트에서 메시지는 대화 맥락을 구성하는 단위이면서 사용자의 전송, 수정, 삭제 행동을 연결하는 대상이다.

### 5.1 외부 상태

```text
MessagePage:
  roomId
  messages: MessageReadItem[]
  firstUnreadSequence nullable
  hasBefore
  hasAfter

MessageReadItem:
  messageId
  roomId
  roomSequence
  sender
  senderReaderKey
  clientMessageId
  type
  content nullable
  visibility
  editVersion
  sentAt
  editedAt nullable
  deletedAt nullable

MESSAGE_SENT event
MESSAGE_EDITED event
MESSAGE_DELETED_BY_USER event
sendMessage command response
editMessage command response
deleteMessage command response
```

### 5.2 사용자 맥락

```text
사용자가 채팅 화면을 보고 있음
사용자가 메시지를 입력 중임
사용자가 메시지 전송을 실행함
사용자가 메시지 수정 또는 삭제를 시도함
사용자가 전송 실패 메시지를 다시 보내려고 함
```

### 5.3 프론트엔드 도메인 모델

`PendingMessage`는 사용자가 보낸 메시지가 서버에서 확정되기 전까지 화면에 표시되는 임시 메시지다.

```text
PendingMessage:
  clientMessageId
  roomId
  sender
  status
  content
  createdAt

PendingMessage.status:
  SENDING
  RETRYING
  FAILED
```

`clientMessageId`는 pending 메시지 추적과 서버 확정 메시지 병합에 사용한다.

`PendingMessage.status`는 서버 확정 전 요청 진행 상태다.

`ClientMessage`는 서버에서 확정된 메시지를 채팅 화면에서 표시하기 위한 모델이다.

```text
ClientMessage:
  messageId
  clientMessageId
  roomId
  sender
  senderReaderKey
  content nullable
  sentAt
  editedAt nullable
  deletedAt nullable
  displayState
  orderKey
  editVersion

ClientMessage.displayState:
  SENT
  EDITED
  DELETED
```

`ClientMessage`는 서버 확정 메시지이므로 `messageId`를 반드시 가진다.

초기 범위에서는 `MessageReadItem.type = TEXT`인 메시지만 `ClientMessage`로 해석하며, `type`을 별도 필드로 보관하지 않는다.

서버 확정 전 메시지는 `PendingMessage`로 분리해서 다룬다.

`clientMessageId`는 현재 기기에서 보낸 pending 메시지와 서버 확정 메시지를 병합하는 데 사용한다.

`senderReaderKey`는 message read receipt count 계산에서 작성자 본인을 제외하기 위한 값이다.

`content`는 삭제된 메시지에서 null일 수 있다.

`orderKey`는 서버가 확정한 roomSequence를 기준으로 한다.

`editVersion`은 수정/삭제 이벤트 병합과 command 충돌 감지에 사용한다.

### 5.4 모델 형성 및 보정

모델 식별:

```text
PendingMessage:
  clientMessageId 기준으로 같은 pending 메시지로 본다.

ClientMessage:
  roomId + messageId 기준으로 같은 메시지로 본다.

서버 확정 전 메시지:
  PendingMessage로 분리해서 해석한다.
```

모델 형성:

```text
사용자가 sendMessage 실행
  -> PendingMessage

MessageReadItem + 채팅 화면 맥락
  -> ClientMessage

MessageReadItem.sentAt / editedAt / deletedAt
  -> ClientMessage.sentAt / editedAt / deletedAt

MessagePage.firstUnreadSequence
  -> 초기 표시 위치

MessageReadItem.visibility / editedAt / deletedAt
  -> ClientMessage.displayState
```

모델 보정:

```text
messageId 우선:
  서버 확정 메시지는 messageId를 기준으로 병합한다.

clientMessageId 병합:
  같은 clientMessageId의 PendingMessage와 서버 확정 메시지가 있으면 서버 확정 메시지로 대체한다.

sendMessage 실패:
  PendingMessage.status를 FAILED로 보정한다.

retry 실행:
  PendingMessage.status를 RETRYING으로 보정한다.

roomSequence 정렬:
  서버가 확정한 roomSequence를 최종 정렬 기준으로 사용한다.

editVersion 비교:
  현재 메시지보다 낮은 editVersion의 수정/삭제 event는 무시한다.

삭제 메시지:
  visibility가 DELETED_BY_USER이면 content와 placeholder를 표시하지 않는다.
```

command response와 server event가 모두 도착할 수 있으므로 메시지 병합은 idempotent해야 한다.

클라이언트는 모든 메시지를 하나의 배열에만 의존해 보관할 필요는 없다.

다만 화면에 렌더링할 때는 `roomSequence`를 기준으로 순서를 보정해야 한다.

초기 UX에서는 삭제된 메시지의 content와 placeholder를 모두 표시하지 않는다.

### 5.5 Capability

`ClientMessageCapability`는 클라이언트가 현재 알고 있는 상태로 수정/삭제 버튼을 보여줄 수 있는지 판단하기 위한 사전 capability다.

최종 수정/삭제 가능 여부는 `editMessage`, `deleteMessage` command 결과로 서버가 확정한다.

```text
ClientMessageCapability:
  messageId
  canEdit
  canDelete
```

Capability 판단 입력:

```text
- ClientMessage
- PendingMessage
- ClientParticipation
- ClientRoomCapability
- ClientMessageAccessScope
- 현재 사용자
```

판단 대상:

```text
canEdit:
  메시지를 수정할 수 있는가

canDelete:
  메시지를 삭제할 수 있는가
```

`editVersion`은 수정/삭제 버튼 표시 여부를 판단하는 주된 조건이 아니라, 서버 command 요청과 이벤트 병합에서 동시성 충돌을 감지하기 위한 기준이다.

### 5.6 Interaction

```text
허용:
  sendMessage 요청
  editMessage 요청
  deleteMessage 요청
  failed message 재시도

차단:
  삭제된 메시지 수정
  현재 참여 구간 밖 메시지 수정/삭제

유도:
  전송 실패 메시지는 재시도를 유도한다.
  삭제된 메시지는 일반 content로 이해되지 않게 한다.
```

---

## 6. 읽음과 표시

읽음은 사용자가 특정 room에서 어디까지 읽었는지를 나타내는 기준이다.

클라이언트에서 읽음은 사용자가 대화를 어디까지 확인했는지와 메시지별로 몇 명이 읽었는지를 표현하는 기준이다.

### 6.1 외부 상태

```text
UnreadCountSummary:
  roomId
  unreadCount

ReadCursorSnapshot:
  roomId
  currentUserReaderKey
  readers: ReadCursorSnapshotItem[]
  generatedAt

ReadCursorSnapshotItem:
  readerKey
  lastReadSequence

READ_CURSOR_UPDATED event
markRead command response
activateReadSession command response
deactivateReadSession command response
```

### 6.2 사용자 맥락

```text
사용자가 내가 참여한 방 목록을 보고 있음
사용자가 room 화면을 보고 있음
사용자가 scroll bottom에 있음
사용자가 앱을 foreground로 전환함
사용자가 room을 이탈함
```

### 6.3 프론트엔드 도메인 모델

`ClientReadState`는 현재 사용자가 특정 room에서 어디까지 읽었는지 나타낸다.

```text
ClientReadState:
  roomId
  userId
  readCursor
```

`readCursor`의 최종 값은 서버가 확정한다.

`ClientReadCursorSnapshot`은 message read receipt count를 계산하기 위한 room 단위 reader cursor snapshot이다.

```text
ClientReadCursorSnapshot:
  roomId
  currentUserReaderKey
  readers
```

`currentUserReaderKey`는 같은 사용자의 다른 connection에서 발생한 읽음 업데이트를 식별하는 데 사용할 수 있다.

`readers`는 ACTIVE member 기준 readerKey별 마지막 읽음 위치다.

`ClientMessageReadReceipt`는 특정 메시지의 읽음 표시를 위한 파생 모델이다.

```text
ClientMessageReadReceipt:
  messageId
  readReceiptCount
  derivedUnreadReceiptCount optional
```

`derivedUnreadReceiptCount`는 필요한 경우 클라이언트가 파생할 수 있는 값이다.

정책 용어:

```text
room unread count:
  현재 사용자가 room에서 아직 읽지 않은 메시지 수

message read receipt count:
  특정 room의 하나의 메시지를 현재 ACTIVE member 중 몇 명이 읽었는지 나타내는 수
```

### 6.4 모델 형성 및 보정

모델 식별:

```text
ClientReadState:
  roomId 기준으로 현재 사용자의 read cursor를 해석한다.

ClientReadCursorSnapshot:
  roomId 기준으로 하나의 snapshot으로 해석한다.

ClientMessageReadReceipt:
  roomId + messageId 기준으로 계산하거나 cache할 수 있다.
```

모델 형성:

```text
UnreadCountSummary
  -> ClientRoomSummary.unreadCount

ReadCursorSnapshot
  -> ClientReadCursorSnapshot

ClientReadCursorSnapshot + ClientMessage
  -> ClientMessageReadReceipt

READ_CURSOR_UPDATED
  -> ClientReadCursorSnapshot 보정 입력
```

모델 보정:

```text
getUnreadCounts:
  ClientRoomSummary.unreadCount를 같은 roomId 기준으로 서버 값으로 덮어쓴다.

getReadCursorSnapshot:
  ClientReadCursorSnapshot을 roomId 기준으로 교체한다.
  기존 snapshot과 merge하지 않는다.

READ_CURSOR_UPDATED:
  readerKey가 기존 snapshot에 있고 lastReadSequence가 증가한 경우에만 반영한다.
  readerKey가 없으면 snapshot이 stale할 수 있으므로 getReadCursorSnapshot으로 다시 보정할 수 있다.

markRead response:
  현재 사용자의 ClientReadState를 서버 확정 값으로 보정한다.
```

message read receipt count는 메시지별 독립 읽음 상태가 아니라 read cursor를 기준으로 계산한 파생 결과다.

```text
readReceiptCount =
  count(snapshot.readers)
  where reader.readerKey != message.senderReaderKey
  and reader.lastReadSequence >= message.roomSequence

derivedUnreadReceiptCount =
  count(snapshot.readers)
  where reader.readerKey != message.senderReaderKey
  and reader.lastReadSequence < message.roomSequence
```

클라이언트는 `readerKey`를 UI에 노출하지 않는다.

read-by 사용자 목록 노출은 초기 범위에 포함하지 않는다.

`READ_CURSOR_UPDATED`는 room unread count를 직접 갱신하는 기준이 아니다.

room unread count는 목록 조회, `getUnreadCounts`, polling, foreground 복귀, 채팅방 복귀 시점에 서버 값으로 보정한다.

### 6.5 Capability

`ClientReadCapability`는 읽음 표시와 읽음 보정 신호를 보낼 수 있는지 판단한다.

```text
ClientReadCapability:
  roomId
  canMarkRead
  canShowRoomUnreadCount
  canShowMessageReadReceiptCount
```

Capability 판단 입력:

```text
- ClientReadState
- ClientReadCursorSnapshot
- ClientMessage
- ClientRoomSummary.unreadCount
- ClientReadSession
- ClientRealtimeConnection
- 현재 페이지 맥락
```

판단 대상:

```text
canMarkRead:
  현재 사용자의 read cursor 보정 요청을 보낼 수 있는가

canShowRoomUnreadCount:
  room 목록에서 room unread count를 표시할 수 있는가

canShowMessageReadReceiptCount:
  메시지 옆에 message read receipt count를 표시할 수 있는가
```

read session은 읽음 데이터가 아니라 사용자가 room 화면을 보고 있음을 서버에 알리기 위한 런타임 신호다.

read session 상태는 실시간 연결 모델에서 다룬다.

### 6.6 Interaction

```text
허용:
  markRead 보정 요청
  room unread count 조회
  read cursor snapshot 조회

차단:
  read cursor 역행 반영
  readerKey UI 노출

유도:
  room 진입, foreground, scroll-to-bottom, room leave 시점에 읽음 보정을 유도한다.
  room unread count와 message read receipt count 표시로 읽음 상태를 인지하게 한다.
```

클라이언트는 새 메시지마다 읽음 갱신 요청을 보내지 않는다.

대신 room enter, foreground, scroll-to-bottom, room leave 같은 적절한 시점에 read cursor 보정을 요청한다.

---

## 7. 실시간 연결

실시간 연결은 서버 상태를 빠르게 반영하고 누락을 복구하기 위한 런타임 연결 상태다.

클라이언트에서 실시간 연결은 WebSocket 연결, room 구독, 재연결, 누락 복구, read session을 함께 조율하는 기준이다.

실시간 연결은 서버 source of truth가 아니며, query 결과와 command 응답을 대체하지 않는다.

### 7.1 외부 상태

```text
WebSocket connection result
subscribeRoom command response
unsubscribeRoom command response
activateReadSession command response
deactivateReadSession command response
MESSAGE_SENT / MESSAGE_EDITED / MESSAGE_DELETED_BY_USER event
READ_CURSOR_UPDATED event
catchUpMessages query result
```

### 7.2 사용자 맥락

```text
사용자가 채팅 화면에 진입함
사용자가 채팅 화면을 이탈함
앱이 foreground/background로 전환됨
네트워크가 끊기거나 복구됨
WebSocket이 재연결됨
server event 누락 가능성이 있음
```

### 7.3 프론트엔드 도메인 모델

`ClientRealtimeConnection`은 현재 클라이언트 runtime의 WebSocket 연결 상태를 나타낸다.

```text
ClientRealtimeConnection:
  status
  lastConnectedAt nullable
  disconnectedAt nullable

ClientRealtimeConnection.status:
  CONNECTING
  CONNECTED
  RECONNECTING
  DISCONNECTED
  AUTH_EXPIRED
```

`ClientRoomSubscription`은 특정 room의 server event를 받을 수 있는지 나타낸다.

```text
ClientRoomSubscription:
  roomId
  status
  lastReceivedSequence nullable
  catchUpRequired
  catchUpTargetSequence nullable

ClientRoomSubscription.status:
  SUBSCRIBING
  SUBSCRIBED
  UNSUBSCRIBING
  UNSUBSCRIBED
  FAILED
```

`lastReceivedSequence`는 클라이언트가 순서대로 반영한 마지막 roomSequence를 추적하기 위한 값이다.

`lastReceivedSequence`가 `null`이면 event sequence의 연속성을 판단할 수 없으므로 event를 직접 적용하지 않고 catch-up 결과로 기준 sequence를 먼저 확정한다.

`catchUpRequired`는 실시간 이벤트만으로 화면이 최신이라고 판단할 수 없는 상태를 나타낸다.

`catchUpTargetSequence`는 gap 감지 시 관찰한 가장 높은 roomSequence이며, catch-up 결과가 누락 구간을 충분히 보정했는지 판단하는 기준이다.

클라이언트는 room event의 roomSequence가 `lastReceivedSequence + 1`보다 크면 gap으로 판단한다. 이미 반영한 sequence는 다시 적용하지 않는다.

catch-up 실행 중 더 새로운 gap이 감지되면 기존 catch-up 성공만으로 `catchUpRequired`를 제거하지 않는다. 이를 위한 실행 version이나 request token은 Realtime Store의 내부 구현으로 둔다.

`ClientReadSession`은 사용자가 room 화면을 보고 있음을 서버에 알리는 런타임 신호 상태다.

```text
ClientReadSession:
  roomId
  status

ClientReadSession.status:
  ACTIVE
  INACTIVE
```

### 7.4 모델 형성 및 보정

모델 식별:

```text
ClientRealtimeConnection:
  현재 사용자 runtime의 하나의 연결 상태로 해석한다.

ClientRoomSubscription:
  roomId 기준으로 room 구독 상태를 해석한다.

ClientReadSession:
  roomId 기준으로 현재 화면의 read session 신호 상태를 해석한다.
```

모델 형성:

```text
WebSocket 연결 시도/결과
  -> ClientRealtimeConnection

subscribeRoom/unsubscribeRoom command response
  -> ClientRoomSubscription

activateReadSession/deactivateReadSession command response
  -> ClientReadSession

roomSequence가 있는 server event 수신
  -> 다음 sequence이면 lastReceivedSequence 보정 입력
  -> 이미 반영한 sequence이면 무시
  -> gap이면 catchUpRequired와 catchUpTargetSequence 보정 입력
```

모델 보정:

```text
WebSocket reconnect:
  필요한 room을 다시 subscribe한다.
  subscription별 lastReceivedSequence 이후를 catchUpMessages로 보정한다.

MESSAGE_SENT / MESSAGE_EDITED / MESSAGE_DELETED_BY_USER:
  ClientMessage 보정 입력으로 사용한다.
  roomSequence, messageId, editVersion 기준으로 idempotent하게 병합한다.

READ_CURSOR_UPDATED:
  ClientReadCursorSnapshot 보정 입력으로 사용한다.

catchUpMessages:
  누락된 message event를 query 결과로 보정한다.
  lastReceivedSequence를 서버가 확정한 sequence까지 전진시킨다.
  현재 catch-up context가 유효하고 target sequence까지 보정했으면
  catchUpRequired를 false로, catchUpTargetSequence를 null로 보정한다.
```

실시간 이벤트가 도착했다는 사실만으로 query 결과보다 최신이라고 단정하지 않는다.

query 결과와 event가 충돌하면 messageId, roomSequence, editVersion, readerKey lastReadSequence 같은 도메인 기준으로 병합한다.

### 7.5 Capability

`ClientRealtimeConnectionCapability`는 현재 runtime이 WebSocket 연결을 시도할 수 있는지 판단한다.

`ClientRoomRealtimeCapability`는 특정 room에서 실시간 동기화 행동을 수행할 수 있는지 판단한다.

```text
ClientRealtimeConnectionCapability:
  canConnectRealtime

ClientRoomRealtimeCapability:
  roomId
  canSubscribe
  canCatchUp
  canSendReadSessionSignal
```

Capability 판단 입력:

```text
- ClientRealtimeConnection
- ClientRoomSubscription
- ClientReadSession
- ClientRoomCapability
- ClientParticipationCapability
- 현재 페이지 맥락
```

판단 대상:

```text
canConnectRealtime:
  WebSocket 연결을 시도할 수 있는가

canSubscribe:
  현재 room을 구독할 수 있는가

canCatchUp:
  누락된 이벤트를 query로 보정할 수 있는가

canSendReadSessionSignal:
  read session 활성화/비활성화 신호를 보낼 수 있는가
```

WebSocket이 연결되었다는 것은 특정 room을 구독했다는 뜻이 아니다.

room을 구독했다는 것도 메시지 조회 권한이나 메시지 전송 권한이 있다는 뜻은 아니다.

연결 끊김은 room이 사용할 수 없다는 뜻이 아니다.

`canSendMessage`는 WebSocket 연결 여부에 직접 의존하지 않는다.

`canSubscribe`는 WebSocket 연결 상태와 room/participation 상태를 함께 기준으로 판단한다.

`canSendReadSessionSignal`은 WebSocket 연결 상태, room 구독 상태, 사용자의 room 화면 진입 상태를 함께 기준으로 판단한다.

### 7.6 Interaction

```text
허용:
  WebSocket 연결 시도
  room 구독 요청
  room 구독 해제 요청
  catchUpMessages 요청
  read session 활성화
  read session 비활성화

차단:
  인증 만료 상태에서 room 구독 유지
  구독되지 않은 room의 실시간 event 수신을 전제로 한 화면 갱신
  catch-up 전 누락 가능 상태를 최종 동기화 상태로 표시

유도:
  재연결 중이면 실시간 반영이 지연될 수 있음을 표현한다.
  재연결 후에는 room 재구독과 누락 복구를 유도한다.
  인증 만료면 재인증 흐름을 유도한다.
```

WebSocket 연결이 끊긴 상태에서는 실시간 수신, room 구독, read session 신호 전송을 사용할 수 없다.

그러나 WebSocket 연결 끊김만으로 `sendMessage`를 차단하지 않는다.

연결 끊김 중 전송한 메시지는 command 응답 또는 재연결 후 catch-up 결과로 보정한다.
