# 오픈 채팅방 클라이언트 도메인 모델

## 1. 목적

이 문서는 오픈 채팅방을 클라이언트에서 해석하기 위한 클라이언트 도메인 모델을 정의한다.

클라이언트 도메인 모델은 서버의 source of truth가 아니라, 화면 상태를 만들기 위한 해석 모델이다.

---

## 2. 도메인 경계

클라이언트 도메인 모델은 서버 확정 상태, 클라이언트 임시 상태, 런타임 상태를 화면 의미로 해석하기 위한 모델이다.

클라이언트 도메인 모델은 백엔드 read model, 서버 이벤트 payload, 클라이언트 pending 상태, 런타임 상태를 조합해 만든다.

백엔드 read model과 1:1로 동일하지 않을 수 있지만, 서버에서 받은 어떤 snapshot 또는 event를 해석한 모델인지는 명확해야 한다.

클라이언트가 하는 것:

```text
- 사용자가 현재 무엇을 볼 수 있는지 해석한다.
- 사용자가 현재 무엇을 할 수 있는지 화면에 표현한다.
- 서버 확정 전 사용자 행동을 임시 상태로 표현한다.
- 실시간 이벤트와 조회 결과를 사용자에게 이해 가능한 화면 변화로 반영한다.
- 실패와 지연을 복구 가능한 사용자 상태로 표현한다.
```

클라이언트가 직접 판단하지 않는 것:

```text
- 최종 참여 권한
- 메시지 조회 가능 범위
- 메시지 수정 가능 여부의 최종 판단
- 메시지 삭제 가능 여부의 최종 판단
- room 종료 여부
- read cursor 최종 값
- unread count 최종 값
- unread receipt count 최종 값
```

클라이언트는 화면에서 명백히 불가능한 행동을 비활성화할 수 있다.

하지만 최종 판단은 서버가 확정한 결과를 따른다.

---

## 3. 모델 그룹

클라이언트 도메인 모델은 다음 흐름으로 묶어 설명한다.

```text
채팅방
  -> 참여
  -> 메시지
  -> 읽음 상태와 표시
  -> 실시간 연결
  -> 파생 화면 상태
```

### 3.1 채팅방

```text
ClientRoom
RoomListItem
RoomDetailView
```

### 3.2 참여

```text
ClientParticipation
```

### 3.3 메시지

```text
MessageItem
PendingMessageItem
clientRequestId
clientMessageId
```

### 3.4 읽음 상태와 표시

```text
ClientReadState
```

### 3.5 실시간 연결

```text
ClientRealtimeState
```

### 3.6 파생 화면 상태

```text
DerivedClientState
```

---

## 4. ClientRoom 모델

ClientRoom 모델은 방 목록과 방 상세에서 사용자가 방을 어떻게 인지하는지 표현하는 클라이언트 모델이다.

### 4.1 역할

`ClientRoom`은 방 목록 항목과 방 상세 상태를 구분해서 표현한다.

`ClientRoom`은 채팅 화면 사용 가능 여부를 단독으로 판단하지 않는다.

채팅 화면 사용 가능 여부는 `ClientParticipation`과 함께 판단한다.

### 4.2 필드

```text
ClientRoom:
  RoomListItem | RoomDetailView

RoomListItem:
  kind = ROOM_LIST_ITEM
  roomId
  name
  accessStatus
  operationStatus
  participantCount
  lastMessagePreview nullable
  unreadCount

RoomDetailView:
  kind = ROOM_DETAIL
  roomId
  name
  accessStatus
  operationStatus
  owner
  participantCount
  currentUserParticipationStatus
```

`RoomListItem`은 `RoomSummary` read model에 대응되는 방 목록 표시 모델이다.

`RoomDetailView`는 `RoomDetail` read model에 대응되는 방 상세 표시 모델이다.

### 4.3 상태

```text
ClientRoom.accessStatus:
  NOT_LOADED
  LIST_VISIBLE
  DETAIL_VISIBLE
  UNAVAILABLE

ClientRoom.operationStatus:
  NORMAL
  SYSTEM_MANAGED
```

### 4.4 규칙

```text
- LIST_VISIBLE은 방 목록에 표시할 수 있다는 뜻이다.
- LIST_VISIBLE만으로는 메시지 조회, 메시지 전송, room 구독을 활성화하지 않는다.
- DETAIL_VISIBLE은 방 상세 정보를 표시할 수 있다는 뜻이다.
- DETAIL_VISIBLE만으로는 현재 사용자가 참여 중이라고 판단하지 않는다.
- 채팅 화면 사용 가능 여부는 ClientParticipation.status == JOINED와 서버 조회/command 결과를 함께 기준으로 판단한다.
- UNAVAILABLE이면 방 상세와 채팅 화면의 입력, 수정, 삭제, 구독 유지 동작을 중단한다.
- UNAVAILABLE 상태의 stale한 room 정보는 다음 사용자 행동의 근거로 사용하지 않는다.
- SYSTEM_MANAGED는 owner 계정 비활성화 등으로 운영 개입이 필요한 상태로 해석한다.
- SYSTEM_MANAGED는 기본적으로 기존 참여자의 채팅 화면 사용 불가를 의미하지 않는다.
- SYSTEM_MANAGED에서 입장, 전송, 수정, 삭제가 제한되는지는 서버 query/command 결과를 따른다.
```

### 4.5 서버 이벤트 반영 기준

```text
ROOM_CLOSED:
  - accessStatus를 UNAVAILABLE 또는 종료 표시 상태로 갱신한다.
  - 채팅 화면을 더 이상 사용 가능 상태로 두지 않는다.

ROOM_OWNER_TRANSFERRED:
  - RoomDetailView가 있으면 owner 표시를 갱신한다.

ROOM_SYSTEM_MANAGED:
  - RoomListItem 또는 RoomDetailView의 operationStatus를 SYSTEM_MANAGED로 갱신한다.

ROOM_JOINED:
  - RoomListItem 또는 RoomDetailView의 participantCount를 보정할 수 있다.

ROOM_LEFT:
  - RoomListItem 또는 RoomDetailView의 participantCount를 보정할 수 있다.

MESSAGE_SENT:
  - RoomListItem의 lastMessagePreview를 보정할 수 있다.
  - unreadCount는 READ_CURSOR_UPDATED 또는 getUnreadCounts 결과를 우선 기준으로 보정한다.

MESSAGE_EDITED:
  - 수정된 메시지가 lastMessagePreview 대상이면 lastMessagePreview를 보정할 수 있다.

MESSAGE_DELETED_BY_USER:
  - 삭제된 메시지가 lastMessagePreview 대상이면 lastMessagePreview를 보정할 수 있다.

READ_CURSOR_UPDATED:
  - RoomListItem의 unreadCount를 보정할 수 있다.
  - lastMessagePreview는 read cursor 변경만으로 바뀌지 않는다.
```

---

## 5. ClientParticipation 모델

ClientParticipation 모델은 특정 roomId에 대한 현재 사용자의 참여 상태를 해석하기 위한 클라이언트 모델이다.

### 5.1 역할

`ClientParticipation`은 참여 전, 참여 중, 나감, 재입장 상태를 화면 의미로 표현한다.

`ClientParticipation`은 현재 사용자가 채팅 화면을 사용할 수 있는지 판단하는 데 사용한다.

`ClientParticipation`은 전체 참여 목록을 표현하는 컬렉션 모델이 아니다.

`ClientParticipation`은 다른 사용자의 참여 상태를 담지 않는다.

### 5.2 필드

```text
ClientParticipation:
  roomId
  status
  joinedAt optional
  leftAt optional
```

### 5.3 상태

```text
ClientParticipation.status:
  NOT_JOINED
  JOIN_PENDING
  JOINED
  JOIN_FAILED
  LEAVE_PENDING
  LEFT
  LEAVE_FAILED
  UNAVAILABLE
```

`NOT_JOINED`는 서버의 저장 상태가 아니라, 현재 사용자가 해당 room에 참여 중이 아님을 나타내는 클라이언트 상태다.

room 상세 화면에서 참여 버튼을 표시하거나 joinRoom 요청 가능 여부를 판단할 때 사용할 수 있다.

클라이언트가 모든 room에 대해 `NOT_JOINED` 상태를 미리 materialize해야 한다는 뜻은 아니다.

`NOT_JOINED`는 방 상세 또는 참여 상태를 조회한 room에 대해 사용할 수 있다.

`ClientParticipation.status`는 `RoomDetail.currentUserParticipationStatus`, 참여 command 응답, room 참여 이벤트를 통해 보정한다.

### 5.4 규칙

```text
- room 상세를 볼 수 있다는 것과 채팅 화면을 사용할 수 있다는 것은 다르다.
- JOINED는 채팅 화면을 사용할 수 있는 상태다.
- LEFT는 해당 참여 생명주기가 종료된 상태다.
- LEFT 상태에서는 채팅 화면을 계속 사용할 수 없다.
- 현재 참여 구간 이전 메시지는 현재 채팅 화면의 메시지로 표시하지 않는다.
- 메시지 조회 가능 범위는 서버 listMessages 또는 catchUpMessages 결과를 기준으로 판단한다.
```

### 5.5 서버 이벤트 반영 기준

```text
ROOM_JOINED:
  - event.userId가 현재 사용자이면 JOINED 상태로 보정할 수 있다.

ROOM_LEFT:
  - event.userId가 현재 사용자이면 LEFT 상태로 보정할 수 있다.
  - 채팅 화면을 더 이상 사용 가능 상태로 두지 않는다.

ROOM_CLOSED:
  - 현재 참여 상태와 무관하게 UNAVAILABLE로 해석한다.
```

본인 참여 상태의 최종 판단은 command 응답 또는 query 결과를 따른다.

---

## 6. MessageItem 모델

MessageItem 모델은 화면에 렌더링되는 메시지 항목을 정의한다.

채팅 화면은 서버 확정 메시지와 클라이언트 임시 메시지를 함께 포함할 수 있다.

### 6.1 역할

`MessageItem`은 사용자가 메시지를 어떤 상태로 인지하는지 표현한다.

`MessageItem`은 서버 확정 메시지와 서버 확정 전 임시 메시지를 구분해서 표현한다.

`PendingMessageItem`은 서버 확정 전 사용자 행동을 즉시 화면에 반영하기 위한 클라이언트 임시 메시지다.

서버 확정 메시지는 서버가 저장에 성공해 `messageId`, `roomSequence`, `editVersion`, `sentAt`을 부여한 메시지다.

`ConfirmedMessageItem`은 sendMessage command response, `MessageReadItem` read model, 메시지 서버 이벤트에 대응되는 서버 확정 메시지 모델이다.

### 6.2 필드

```text
MessageItem:
  ConfirmedMessageItem | PendingMessageItem

ConfirmedMessageItem:
  kind = CONFIRMED_MESSAGE
  messageId
  roomId
  sender
  status
  type
  content nullable
  visibility
  roomSequence
  clientMessageId
  editVersion
  unreadReceiptCount optional
  sentAt
  editedAt nullable
  deletedAt nullable

PendingMessageItem:
  kind = PENDING_MESSAGE
  clientMessageId
  roomId
  sender
  status
  content
  createdAt
```

### 6.3 상태

```text
ConfirmedMessageItem.status:
  SENT
  EDITED
  DELETED
  SENT_WITH_ERROR

PendingMessageItem.status:
  SENDING
  FAILED
  RETRYING
```

### 6.4 unread receipt count

unread receipt count는 `MessageItem`에 붙는 메시지 단위 표시값이다.

클라이언트는 메시지 옆에 read receipt count 자체가 아니라 unread receipt count를 표시할 수 있다.

개념적으로 다음과 같이 해석한다.

```text
unreadReceiptCount =
  receiptTargetMemberCount - readReceiptCount
```

`receiptTargetMemberCount`는 작성자 본인을 제외한 읽음 표시 대상 참여자 수다.

작성자 본인은 unread receipt count에 포함하지 않는다.

### 6.5 clientRequestId

`clientRequestId`는 idempotency를 위한 요청 식별자다.

클라이언트는 idempotency가 필요한 사용자 행동마다 `clientRequestId`를 생성한다.

`clientRequestId`는 UUID를 사용한다.

같은 사용자 행동을 재시도하는 경우 같은 `clientRequestId`를 유지한다.

`clientRequestId`는 메시지 말풍선 식별자가 아니라 요청 중복 처리를 위한 값이다.

### 6.6 clientMessageId

`clientMessageId`는 메시지 전송 요청의 idempotency와 서버 확정 전 `PendingMessageItem` 추적에 사용하는 메시지 식별자다.

클라이언트는 메시지 작성 시 `clientMessageId`를 생성한다.

`clientMessageId`는 UUID를 사용한다.

서버 확정 메시지가 도착하면 `clientMessageId`를 기준으로 `PendingMessageItem`과 병합할 수 있다.

현재 서버 설계에서는 `clientMessageId`가 서버 확정 메시지에도 저장된다.

`clientMessageId`는 화면에서 `SENDING`, `FAILED`, `RETRYING` 메시지를 안정적으로 추적하기 위한 값이다.

### 6.7 규칙

```text
- ConfirmedMessageItem은 서버 확정 메시지를 기반으로 한다.
- PendingMessageItem은 서버 확정 전 임시 메시지를 기반으로 한다.
- 서버 확정 메시지가 도착하면 PendingMessageItem은 ConfirmedMessageItem으로 대체된다.
- 같은 메시지가 여러 경로로 도착해도 하나의 MessageItem으로 병합한다.
- 메시지의 최종 순서는 서버가 확정한 room 단위 메시지 순서를 따른다.
- PendingMessageItem은 서버 확정 전 임시 위치에 표시될 수 있다.
- DELETED 상태에서는 content를 일반 메시지처럼 노출하지 않는다.
- 삭제된 메시지를 placeholder로 표시할지 목록에서 제외할지는 UI 정책에서 정한다.
- unreadReceiptCount는 메시지 단위 표시값이다.
```

### 6.8 서버 이벤트 반영 기준

sendMessage command 응답은 요청한 connection에서 메시지 전송 성공/실패를 판단하는 기준이다.

`MESSAGE_SENT` 이벤트는 room 구독자, 같은 사용자의 다른 connection, 재전파 경로에서 같은 메시지를 동기화하기 위한 입력이다.

sender connection도 `MESSAGE_SENT`를 수신할 수 있으므로 클라이언트는 같은 메시지를 idempotent하게 병합한다.

```text
MESSAGE_SENT:
  - messageId 기준으로 ConfirmedMessageItem을 생성하거나 병합한다.
  - clientMessageId가 같은 PendingMessageItem이 있으면 ConfirmedMessageItem으로 대체한다.
  - roomSequence 기준으로 메시지 목록에서의 위치를 정한다.

MESSAGE_EDITED:
  - messageId 기준으로 대상 MessageItem을 찾는다.
  - 수신한 editVersion이 현재 MessageItem의 editVersion보다 크면 content, editedAt, editVersion을 갱신한다.
  - 오래된 editVersion은 무시한다.

MESSAGE_DELETED_BY_USER:
  - messageId 기준으로 대상 MessageItem을 찾는다.
  - 수신한 editVersion이 현재 MessageItem의 editVersion보다 크면 DELETED 상태로 갱신한다.
  - content는 일반 메시지처럼 노출하지 않는다.

READ_RECEIPT_UPDATED:
  - messageId 기준으로 대상 MessageItem을 찾는다.
  - 대상 MessageItem의 unreadReceiptCount를 갱신한다.
```

---

## 7. ClientReadState 모델

ClientReadState 모델은 room 단위 read cursor, read session, unread count를 화면 의미로 표현한다.

### 7.1 역할

`ClientReadState`는 사용자가 특정 room에서 어디까지 읽었는지와 아직 읽지 않은 메시지가 얼마나 있는지 표현한다.

### 7.2 필드

```text
ClientReadState:
  roomId
  userId
  readCursor
  readSessionStatus
  unreadCount
```

### 7.3 상태

```text
ClientReadState.readSessionStatus:
  READ_HIDDEN
  READ_VISIBLE
  READ_SYNC_PENDING
  READ_SYNCED
  READ_VISIBLE_WITH_STALE_COUNT
```

### 7.4 규칙

```text
- read cursor의 source of truth는 서버다.
- 각 사용자는 참여 중인 room마다 자신의 read cursor를 가진다.
- read cursor 이후의 메시지는 해당 사용자에게 unread 메시지로 해석한다.
- 클라이언트가 먼저 읽음 표시를 갱신하더라도 서버가 확정한 read cursor와 다르면 서버 값을 따른다.
- read session은 사용자가 채팅 화면을 보고 있음을 서버에 알리는 런타임 신호다.
- markRead는 read session과 별개로 클라이언트가 적절한 시점에 보내는 read cursor 보정 요청이다.
- 클라이언트는 새 메시지마다 markRead를 호출하지 않는다.
- unread count의 최종 값은 서버 조회 결과로 보정한다.
```

### 7.5 서버 이벤트 반영 기준

```text
READ_CURSOR_UPDATED:
  - event.userId가 현재 사용자이면 readCursor를 갱신한다.
  - 수신한 lastReadSequence가 현재 readCursor보다 작으면 무시한다.
  - unreadCount는 갱신된 readCursor 기준으로 보정할 수 있다.
```

read cursor와 unread count의 최종 값은 서버 조회 결과를 따른다.

---

## 8. ClientRealtimeState 모델

ClientRealtimeState 모델은 실시간 연결, room 구독, 누락 복구 상태를 화면 의미로 표현한다.

### 8.1 역할

`ClientRealtimeState`는 채팅방 화면을 최신 상태에 가깝게 유지하기 위한 연결 상태를 나타낸다.

### 8.2 필드

```text
ClientRealtimeState:
  connectionStatus
  subscriptionStatusByRoom
  catchUpStatusByRoom
  lastHandledSequenceByRoom
```

### 8.3 상태

```text
ClientRealtimeState.connectionStatus:
  DISCONNECTED
  CONNECTING
  CONNECTED
  RECONNECTING

RoomSubscriptionStatus:
  NOT_SUBSCRIBED
  SUBSCRIBE_PENDING
  SUBSCRIBED
  SUBSCRIBE_FAILED
  UNSUBSCRIBE_PENDING
  SUBSCRIPTION_STALE

CatchUpStatus:
  CATCH_UP_PENDING
  CATCH_UP_FAILED
```

### 8.4 규칙

```text
- 하나의 WebSocket connection은 여러 room을 subscribe할 수 있다.
- room별 구독 상태, 누락 복구 상태, 마지막 처리 sequence는 roomId 기준으로 관리한다.
- 실시간 이벤트는 화면을 빠르게 갱신하기 위한 입력이다.
- 실시간 이벤트를 최종 상태로 간주하지 않는다.
- 실시간 이벤트는 조회 결과 또는 누락 복구 결과로 보정될 수 있어야 한다.
- 실시간 연결이 되어 있어도 모든 room 이벤트를 받는 것은 아니다.
- 채팅 화면을 이탈하거나 room에서 나가면 구독과 read session을 함께 정리해야 한다.
- 연결이 끊겨도 기존 화면 상태를 즉시 폐기하지 않는다.
- 클라이언트는 마지막으로 처리한 메시지 이후의 데이터를 복구한다.
- 누락 복구 중에는 기존 메시지 목록을 유지하되, 화면이 최신이 아닐 수 있음을 표시한다.
```

### 8.5 서버 이벤트 처리 기준

```text
- eventId 기준으로 중복 이벤트를 무시한다.
- 구독하지 않은 room의 이벤트는 적용하지 않는다.
- 메시지 이벤트는 roomSequence 기준으로 정렬한다.
- 마지막으로 처리한 roomSequence 이후 gap이 의심되면 afterSequence catch-up을 요청한다.
- 수정/삭제 이벤트는 editVersion 기준으로 오래된 이벤트를 무시한다.
- Direct WebSocket event 유실은 서버 조회 결과로 복구한다.
```

---

## 9. DerivedClientState

DerivedClientState는 서버 확정 상태, 클라이언트 임시 상태, 런타임 상태를 조합해 만든 화면 판단용 상태다.

DerivedClientState는 서버의 source of truth가 아니다.

### 9.1 필드

```text
DerivedClientState:
  canEnterRoom
  canSendMessage
  canEditMessage
  canDeleteMessage
  visibleMessages
  roomPreview
  unreadBadge
  unreadReceiptBadge
  connectionBanner
```

### 9.2 규칙

```text
- 파생 상태는 화면 판단을 돕기 위한 값이다.
- 파생 상태는 서버 확정 상태를 대체하지 않는다.
- canEditMessage, canDeleteMessage는 최종 권한 판단이 아니라 UI 노출 판단이다.
- visibleMessages는 현재 참여 기준, 메시지 표시 상태, PendingMessageItem을 반영한 화면 메시지 목록이다.
- connectionBanner는 연결 끊김, 재연결, 누락 복구 상태를 사용자에게 알리기 위한 화면 상태다.
```

### 9.3 서버 이벤트와의 관계

`DerivedClientState`는 서버 이벤트를 직접 저장하지 않는다.

서버 이벤트가 `ClientRoom`, `ClientParticipation`, `MessageItem`, `ClientReadState`, `ClientRealtimeState`를 갱신하면 `DerivedClientState`는 그 결과로 다시 계산된다.
