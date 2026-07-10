# 오픈 채팅방 Query 명세

## 1. 목적

Query는 데이터를 조회하기 위한 읽기 작업이다.

Query는 ChatRoom, ChatRoomMembership, ChatMessage, ChatReadCursor 같은 도메인 상태를 변경하지 않는다.

Query가 반환하는 read model은 서버 내부 source of truth가 아니다.

하지만 클라이언트에게 반환된 read model은 해당 조회 시점의 authoritative server response로 취급한다.

클라이언트는 read model을 화면 상태의 기준으로 사용하되, 이후 WebSocket event, command response, 재조회 결과로 갱신될 수 있음을 전제한다.

---

## 2. Query 작성 형식

각 query는 다음 형식으로 정의한다.

```text
목적
Actor
입력 필드
사전 조건
반환 모델
조회 기준
실패 기준
```

---

## 3. Read model

Read model은 query 응답을 설명하기 위한 모델이다.

Read model은 ChatRoom, ChatRoomMembership, ChatMessage, ChatReadCursor 같은 원천 도메인 모델을 조합하거나 계산한 결과다.

Read model은 query 실행 시점의 snapshot이다.

서버 내부 도메인 상태가 변경되면 이전에 반환된 read model은 stale해질 수 있다.

클라이언트는 WebSocket event, command response, 재조회 결과를 통해 stale한 read model을 갱신한다.

초기 구현에서는 DB query로 계산할 수 있고, 비용이 커지면 projection 또는 cache로 대체할 수 있다.

### 3.1 RoomSummary

RoomSummary는 방 목록 조회에서 사용하는 room 요약 모델이다.

```text
RoomSummary:
  roomId
  name
  operationStatus
  participantCount
  lastMessagePreview nullable
  unreadCount
```

```text
participantCount:
  ACTIVE ChatRoomMembership 수

lastMessagePreview:
  room에서 마지막으로 표시 가능한 메시지의 요약
  DELETED_BY_USER 메시지는 일반 content를 노출하지 않는다.

unreadCount:
  현재 사용자의 ChatReadCursor.lastReadSequence 이후 메시지 수
```

### 3.2 RoomDetail

RoomDetail은 방 상세 조회에서 사용하는 room 상세 모델이다.

```text
RoomDetail:
  roomId
  name
  owner
  operationStatus
  participantCount
  currentUserParticipationStatus
```

```text
OwnerSummary:
  userId
  displayName
```

`currentUserParticipationStatus`는 현재 사용자가 해당 room의 채팅 화면을 사용할 수 있는지 판단하기 위한 조회 응답 상태다.

```text
currentUserParticipationStatus:
  JOINED
  NOT_JOINED
  LEFT
  UNAVAILABLE
```

### 3.3 MessagePage

MessagePage는 메시지 목록 조회와 누락 복구 조회에서 사용하는 메시지 페이지 모델이다.

```text
MessagePage:
  roomId
  messages: MessageReadItem[]
  hasBefore
  hasAfter
```

### 3.4 MessageReadItem

MessageReadItem은 사용자에게 표시할 수 있는 메시지 조회 모델이다.

```text
MessageReadItem:
  messageId
  roomId
  roomSequence
  sender
  clientMessageId
  type
  content nullable
  visibility
  editVersion
  sentAt
  editedAt nullable
  deletedAt nullable
  unreadReceiptCount optional
```

```text
SenderSummary:
  userId
  displayName
```

`content`는 `visibility == DELETED_BY_USER`인 경우 일반 사용자 응답에서 null 또는 비노출 상태로 반환할 수 있다.

`unreadReceiptCount`는 메시지 옆에 안 읽은 사람 수를 표시하는 경우 포함할 수 있다.

### 3.5 UnreadCountSummary

UnreadCountSummary는 room별 unread count 조회 모델이다.

```text
UnreadCountSummary:
  roomId
  unreadCount
```

### 3.6 ReadReceiptSummary

ReadReceiptSummary는 메시지별 읽음 표시 조회 모델이다.

ReadReceiptSummary는 메시지별 독립 읽음 상태가 아니라 `ChatReadCursor.lastReadSequence`를 기준으로 계산한 파생 결과다.

```text
ReadReceiptSummary:
  messageId
  roomSequence
  readReceiptCount
  unreadReceiptCount
```

`readReceiptCount`는 해당 메시지를 읽은 참여자 수다.

`unreadReceiptCount`는 해당 메시지를 아직 읽지 않은 표시 대상 참여자 수다.

작성자 본인은 두 count의 표시 대상에서 제외한다.

---

## 4. Query 목록

### 4.1 채팅방 Queries

```text
listRooms
getRoomDetail
```

### 4.2 메시지 Queries

```text
listMessages
catchUpMessages
```

### 4.3 읽음 상태와 표시 Queries

```text
getUnreadCounts
getReadReceiptCounts
```

---

## 5. 채팅방 Queries

### 5.1 listRooms

#### 목적

사용자가 조회 가능한 오픈 채팅방 목록을 조회한다.

#### Actor

인증된 USER

#### 입력 필드

```text
cursor optional
limit
```

#### 사전 조건

```text
- 인증 token이 유효해야 한다.
- limit은 1 이상이어야 한다.
- limit은 서비스 정책의 최대 조회 개수 이하여야 한다.
- cursor가 제공된 경우 서버가 발급한 유효한 cursor여야 한다.
```

#### 반환 모델

```text
RoomSummary[]
```

#### 조회 기준

```text
- ChatRoom.status == ACTIVE인 room만 반환한다.
- CLOSED room은 일반 사용자 목록 조회에서 제외한다.
- SYSTEM_MANAGED room은 서비스 정책에 따라 목록 노출 여부가 달라질 수 있다.
- 정렬 기준은 서비스 정책으로 정하되, cursor는 동일 정렬 기준으로 다음 페이지를 이어서 조회할 수 있어야 한다.
- 같은 정렬 값이 있는 room을 안정적으로 페이지네이션할 수 있도록 tie-breaker를 사용한다.
```

#### 실패 기준

```text
- 인증 token이 유효하지 않다.
- limit이 없거나 허용 범위를 벗어난다.
- cursor가 제공되었지만 유효하지 않다.
```

---

### 5.2 getRoomDetail

#### 목적

사용자가 접근 가능한 오픈 채팅방 상세 정보를 조회한다.

#### Actor

인증된 USER

#### 입력 필드

```text
roomId
```

#### 사전 조건

```text
- 인증 token이 유효해야 한다.
- room이 존재해야 한다.
- room.status == ACTIVE
```

#### 반환 모델

```text
RoomDetail
```

#### 조회 기준

```text
- CLOSED room은 일반 사용자 상세 조회에서 제외한다.
- SYSTEM_MANAGED room은 서비스 정책에 따라 상세 조회 가능 여부가 달라질 수 있다.
- RoomDetail은 owner, participantCount, currentUserParticipationStatus를 포함한다.
```

#### 실패 기준

```text
- 인증 token이 유효하지 않다.
- roomId가 없다.
- room이 존재하지 않는다.
- room.status != ACTIVE
- 서비스 정책상 actor가 해당 room 상세를 조회할 수 없다.
```

---

## 6. 메시지 Queries

### 6.1 listMessages

#### 목적

room의 메시지를 조회한다.

#### Actor

ACTIVE member

#### 입력 필드

```text
roomId
beforeSequence optional
afterSequence optional
limit
```

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- actor의 ChatRoomMembership.status == ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재해야 한다.
- limit은 1 이상이어야 한다.
- limit은 서비스 정책의 최대 조회 개수 이하여야 한다.
- beforeSequence와 afterSequence를 동시에 제공하지 않는다.
```

#### 반환 모델

```text
MessagePage
```

#### 조회 기준

```text
- 현재 ChatParticipationPeriod 범위 안의 메시지만 반환한다.
- message.roomSequence > currentChatParticipationPeriod.joinedSequence
- beforeSequence가 있으면 해당 sequence 이전 메시지를 조회한다.
- afterSequence가 있으면 해당 sequence 이후 메시지를 조회한다.
- beforeSequence와 afterSequence가 모두 없으면 현재 참여 구간에서 가장 최근 메시지를 조회한다.
- 조회 결과는 roomSequence ASC 기준으로 반환한다.
- 최근 메시지를 조회할 때도 클라이언트가 그대로 렌더링할 수 있도록 최종 반환 순서는 roomSequence ASC를 유지한다.
- DELETED_BY_USER 메시지는 일반 사용자 응답에서 content를 노출하지 않는다.
- DELETED_BY_USER 메시지는 message row 자체를 삭제하지 않고 삭제 표시 상태로 반환할 수 있다.
- 조회 범위 밖의 sequence가 기준값으로 들어와도 최종 결과는 현재 ChatParticipationPeriod 범위로 제한한다.
```

#### 실패 기준

```text
- roomId가 없다.
- room이 존재하지 않는다.
- room.status != ACTIVE
- actor가 해당 room의 ACTIVE member가 아니다.
- 현재 열린 ChatParticipationPeriod가 존재하지 않는다.
- limit이 없거나 허용 범위를 벗어난다.
- beforeSequence와 afterSequence가 동시에 제공되었다.
- beforeSequence 또는 afterSequence가 유효한 sequence 형식이 아니다.
```

---

### 6.2 catchUpMessages

#### 목적

WebSocket 재연결 또는 이벤트 유실 이후 누락된 메시지를 복구한다.

#### Actor

ACTIVE member

#### 입력 필드

```text
roomId
afterSequence
limit
```

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- actor의 ChatRoomMembership.status == ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재해야 한다.
- afterSequence가 있어야 한다.
- afterSequence는 0 이상이어야 한다.
- limit은 1 이상이어야 한다.
- limit은 서비스 정책의 최대 조회 개수 이하여야 한다.
```

#### 반환 모델

```text
MessagePage
```

#### 조회 기준

```text
- afterSequence 이후 메시지를 roomSequence ASC 기준으로 반환한다.
- 현재 ChatParticipationPeriod 범위 밖의 메시지는 반환하지 않는다.
- 실제 조회 하한은 max(afterSequence, currentChatParticipationPeriod.joinedSequence)이다.
- afterSequence가 ChatRoom.lastMessageSequence 이상이면 빈 결과를 반환한다.
- DELETED_BY_USER 메시지는 일반 사용자 응답에서 content를 노출하지 않는다.
- 클라이언트는 roomSequence 기준으로 중복 제거와 정렬을 수행할 수 있어야 한다.
```

#### 실패 기준

```text
- roomId가 없다.
- room이 존재하지 않는다.
- room.status != ACTIVE
- actor가 해당 room의 ACTIVE member가 아니다.
- 현재 열린 ChatParticipationPeriod가 존재하지 않는다.
- afterSequence가 없다.
- afterSequence가 유효한 sequence 형식이 아니다.
- limit이 없거나 허용 범위를 벗어난다.
```

---

## 7. 읽음 상태와 표시 Queries

### 7.1 getUnreadCounts

#### 목적

사용자가 참여 중인 room별 unread count를 조회한다.

#### Actor

인증된 USER

#### 입력 필드

```text
roomIds optional
```

#### 사전 조건

```text
- 인증 token이 유효해야 한다.
- roomIds가 제공된 경우 중복 roomId는 하나로 취급한다.
```

#### 반환 모델

```text
UnreadCountSummary[]
```

#### 조회 기준

```text
- actor가 ACTIVE member인 room만 대상으로 한다.
- 현재 열린 ChatParticipationPeriod가 없는 room은 계산 대상에서 제외한다.
- unread count는 ChatReadCursor.lastReadSequence와 currentChatParticipationPeriod.joinedSequence 중 큰 값 이후 메시지 수로 계산한다.
- roomIds가 제공되지 않으면 actor가 ACTIVE member인 모든 ACTIVE room을 대상으로 한다.
- roomIds가 제공되면 해당 목록 중 actor가 ACTIVE member인 ACTIVE room만 대상으로 한다.
- CLOSED room은 계산 대상에서 제외한다.
- DELETED_BY_USER 메시지는 roomSequence 기준 unread 계산에 포함할 수 있으며, content 노출 여부와 unread 계산은 분리한다.
```

#### 실패 기준

```text
- 인증 token이 유효하지 않다.
- roomIds 형식이 유효하지 않다.
```

---

### 7.2 getReadReceiptCounts

#### 목적

현재 표시 중인 메시지 구간의 read receipt count를 서버 계산값으로 보정한다.

#### Actor

ACTIVE member

#### 입력 필드

```text
roomId
fromSequence
toSequence
```

#### 사전 조건

```text
- room이 존재해야 한다.
- room.status == ACTIVE
- actor의 ChatRoomMembership.status == ACTIVE
- 현재 열린 ChatParticipationPeriod가 존재해야 한다.
- fromSequence와 toSequence가 있어야 한다.
- fromSequence <= toSequence
- 조회 구간이 현재 ChatParticipationPeriod 범위 안에 있어야 한다.
```

#### 반환 모델

```text
ReadReceiptSummary[]
```

#### 조회 기준

```text
- 조회 구간에 포함된 각 메시지에 대해 read receipt count를 계산한다.
- 조회 시점에 ACTIVE member인 사용자 중 ChatReadCursor.lastReadSequence가 각 message.roomSequence 이상인 멤버 수를 계산한다.
- 각 message sender는 해당 메시지의 read receipt count에서 제외한다.
- 같은 userId가 여러 connection으로 접속해 있어도 하나의 reader로 계산한다.
- 대형 room에서는 projection 또는 cache를 사용할 수 있다.
```

#### 실패 기준

```text
- roomId가 없다.
- fromSequence 또는 toSequence가 없다.
- fromSequence > toSequence
- room이 존재하지 않는다.
- room.status != ACTIVE
- actor가 해당 room의 ACTIVE member가 아니다.
- 현재 열린 ChatParticipationPeriod가 존재하지 않는다.
- message가 존재하지 않는다.
- message.roomId != roomId
- message.roomSequence가 현재 ChatParticipationPeriod 범위 밖이다.
```
