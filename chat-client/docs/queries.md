# 오픈 채팅방 클라이언트 Query 반영 기준

## 1. 목적

이 문서는 클라이언트가 서버 query 결과를 프론트엔드 도메인 모델에 반영하는 기준을 정의한다.

query는 서버가 확정한 외부 상태를 조회하는 경로다.

query는 API path, response JSON, client cache library 사용법을 정의하지 않는다.

---

## 2. Query 처리 원칙

query는 프론트엔드 도메인 모델을 보정하는 주요 근거다.

클라이언트는 query 결과를 그대로 화면에 복사하지 않고, 사용자 맥락과 함께 해석해 프론트엔드 도메인 모델에 반영한다.

```text
query 결과
  -> 외부 상태
  -> 프론트엔드 도메인 모델 보정
  -> 인터랙션 규칙 갱신
```

query 결과는 다음 성격을 가진다.

```text
서버가 확정한 응답:
  query 결과는 서버가 해당 시점에 계산해 반환한 authoritative response다.

snapshot:
  query 결과는 조회 이후 stale해질 수 있다.

보정 입력:
  query 결과는 기존 화면 상태, pending 요청, server event 반영 결과를 보정할 수 있다.

사용자 맥락 의존:
  같은 query 결과라도 사용자가 목록을 보는지, 상세를 보는지, 채팅 화면에 있는지에 따라 반영 대상이 달라질 수 있다.
```

클라이언트는 query 결과를 화면의 기준 상태로 사용하지만, 이후 command 응답, server event, 재조회 결과로 다시 보정될 수 있음을 전제한다.

---

## 3. 공통 반영 규칙

### 3.1 stale 처리

query 요청을 보낸 뒤 더 최신 query 결과나 server event가 먼저 반영될 수 있다.

클라이언트는 오래된 query 결과가 최신 프론트엔드 도메인 모델을 덮어쓰지 않도록 해야 한다.

동일한 대상에 대해 여러 query 결과가 도착하면 더 최신 요청 또는 더 최신 sequence 기준 결과를 우선한다.

최신성 기준은 도메인 요소별로 다르다.

```text
채팅방:
  더 나중에 확인한 RoomDetail 또는 명시적인 room event를 우선한다.

메시지:
  roomSequence, editVersion을 기준으로 오래된 메시지 상태를 덮어쓰지 않는다.

읽음:
  서버가 계산한 unread count와 reader cursor snapshot을 보정 기준으로 사용한다.

실시간 연결:
  lastReceivedSequence와 catchUpRequired를 기준으로 누락 복구 필요 여부를 판단한다.
```

### 3.2 기존 상태 병합

query 결과는 기존 상태를 전체 교체할 수도 있고, 일부 필드만 보정할 수도 있다.

병합 기준은 비즈니스 요소별로 다르다.

```text
채팅방:
  listRooms는 ClientRoomSummary 목록을 보정한다.
  getRoomDetail은 ClientRoomDetail, ClientParticipation, capability 계산 근거를 보정한다.

참여:
  getRoomDetail 결과는 현재 사용자 참여 상태를 보정한다.
  진행 중인 join/leave 요청과 충돌할 수 있으므로 PendingParticipationAction과 분리해서 병합한다.

메시지:
  listMessages와 catchUpMessages는 messageId 기준으로 기존 ClientMessage와 병합한다.
  clientMessageId가 같은 PendingMessage가 있으면 서버 확정 메시지로 대체할 수 있다.

읽음:
  getUnreadCounts는 room 단위 unread count를 보정한다.
  getReadCursorSnapshot은 메시지별 read receipt count 계산에 필요한 reader cursor snapshot을 보정한다.

실시간 연결:
  catchUpMessages는 메시지뿐 아니라 ClientRoomSubscription의 catchUpRequired, lastReceivedSequence를 보정한다.
```

### 3.3 pending 요청과의 관계

query 결과는 서버가 확정한 외부 상태다.

진행 중인 command 요청은 query 결과와 별도로 유지한다.

query 결과는 pending 요청을 보정할 수 있지만, pending 요청의 성공 또는 실패를 항상 단독으로 확정하지는 않는다.

예를 들어 사용자가 `sendMessage`를 실행하면 클라이언트는 서버 확정 전 `PendingMessage`를 화면에 표시할 수 있다.

그 직후 `listMessages` 결과에 해당 메시지가 없더라도, 클라이언트는 `PendingMessage`를 즉시 제거하지 않는다.

`PendingMessage`는 command 응답, `MESSAGE_SENT` event, 명확한 실패 응답으로 확정하거나 실패 처리한다.

참여도 같은 기준을 따른다.

`PendingParticipationAction`은 query 결과만으로 무조건 제거하지 않고, command 응답, 명확한 `RoomDetail.currentUserParticipationStatus`, 또는 관련 server event로 서버 상태가 확인된 경우에 보정한다.

### 3.4 server event와의 관계

query와 server event는 같은 프론트엔드 도메인 모델을 갱신할 수 있다.

예를 들어 같은 메시지는 `sendMessage` 응답, `MESSAGE_SENT` event, `listMessages` 결과, `catchUpMessages` 결과로 들어올 수 있다.

클라이언트는 같은 메시지, 같은 room, 같은 읽음 표시가 여러 경로로 도착해도 중복 렌더링하지 않는다.

메시지는 `messageId`, `roomSequence`, `editVersion`을 기준으로 병합한다.

unread count와 read cursor snapshot은 server event로 빠르게 반영할 수 있지만, query 결과로 다시 보정할 수 있다.

`unreadCount`는 사용자별 read cursor가 반영된 값이므로 `MESSAGE_SENT` event만으로 최종 확정하지 않고 `getUnreadCounts` 결과로 보정한다.

초기 설계에서 room 목록 summary는 별도 WebSocket event로 갱신하지 않는다.

`lastMessagePreview`는 `listRooms` 결과로 보정하고, `unreadCount`는 `getUnreadCounts` 결과로 보정한다.

server event가 유실되어도 메시지는 `catchUpMessages` 또는 `listMessages`로, room 상태는 `listRooms` 또는 `getRoomDetail`로, unread count와 read cursor snapshot은 관련 read query로 보정할 수 있어야 한다.

### 3.5 loading, empty, error

loading, empty, error는 query 처리 상태이며 서버 도메인 상태가 아니다.

empty result는 조회 범위에 표시할 데이터가 없다는 뜻이지, 항상 room이 사용할 수 없다는 뜻은 아니다.

network error나 timeout은 기존 프론트엔드 도메인 모델을 즉시 unavailable로 바꾸는 근거가 아니다.

서버가 room 없음, CLOSED, 접근 불가를 명확히 응답한 경우에만 unavailable 흐름으로 보정한다.

이 문서에서는 loading, empty, error의 상세 UI 표현을 정의하지 않는다.

---

## 4. Query 작성 형식

각 query는 다음 형식으로 정리한다.

```text
목적
사용자 맥락
외부 상태
반영 대상
반영 기준
인터랙션 영향
```

```text
반영 기준:
  query 결과를 어떤 프론트엔드 도메인 모델에 어떤 기준으로 병합하거나 보정하는지 설명한다.

인터랙션 영향:
  query 결과로 인해 사용자가 할 수 있는 행동, 차단되는 행동, 유도되는 행동이 어떻게 달라지는지 설명한다.
```

---

## 5. 채팅방 Query

### 5.1 listRooms

#### 목적

사용자가 발견할 수 있는 오픈 채팅방 목록을 가져온다.

#### 사용자 맥락

```text
사용자가 room 목록 화면에 진입함
사용자가 room 목록을 새로고침함
사용자가 목록 다음 페이지를 요청함
사용자가 다른 화면에서 돌아와 목록을 다시 확인함
```

#### 외부 상태

```text
RoomSummary[]
```

#### 반영 대상

```text
ClientRoomSummary[]
```

#### 반영 기준

`RoomSummary`는 `ClientRoomSummary`를 보정하는 외부 상태다.

```text
반영 방식:
  그대로 반영:
    roomId
    name
    operationStatus
    participantCount
    lastMessagePreview
    unreadCount
```

`listRooms` 결과는 현재 목록 조회 조건에서 표시할 `ClientRoomSummary` 목록으로 사용한다.

같은 `roomId`가 이미 있으면 기존 `ClientRoomSummary`를 새 `RoomSummary` 결과로 갱신한다.

`listRooms` 결과에 특정 room이 없다는 사실만으로 이미 열려 있는 `ClientRoomDetail`을 `UNAVAILABLE`로 보정하지 않는다.

다만 명시적으로 room이 접근 불가로 확인된 경우에는 stale한 room 화면을 계속 사용할 수 있게 두지 않는다.

전체 목록 새로고침 결과에서 사라진 room은 목록에서 제거할 수 있다.

페이지네이션, 검색, 필터 결과에 특정 room이 없다는 사실만으로 기존 room을 제거하지 않는다.

#### 인터랙션 영향

목록에 표시된 room은 상세 진입 후보가 된다.

`SYSTEM_MANAGED` room은 목록에서 별도 안내가 필요할 수 있지만, 그것만으로 채팅 불가로 판단하지 않는다.

목록 조회가 실패해도 기존 `ClientRoomSummary` 목록은 즉시 비우지 않는다.

---

### 5.2 getRoomDetail

#### 목적

특정 room의 상세 정보와 현재 사용자의 참여 상태를 가져온다.

#### 사용자 맥락

```text
사용자가 room 상세 화면에 진입함
사용자가 room 참여 전 정보를 확인함
사용자가 채팅 화면 진입 가능 여부를 판단해야 함
사용자가 stale한 room 정보로 행동하려고 함
```

#### 외부 상태

```text
RoomDetail
```

#### 반영 대상

```text
직접 반영:
  ClientRoomDetail
  ClientParticipation

재계산 대상:
  ClientRoomCapability
  ClientParticipationCapability
  ClientMessageAccessScope
```

#### 반영 기준

`RoomDetail`은 `ClientRoomDetail`과 `ClientParticipation`에 직접 반영되고, 그 결과로 capability와 메시지 접근 범위를 다시 계산한다.

```text
RoomDetail
  -> ClientRoomDetail
     그대로 반영:
       roomId
       name
       operationStatus
       owner
       participantCount

  -> ClientParticipation
     필드명 변경 반영:
       currentUserParticipationStatus -> status

  -> 재계산
     ClientRoomCapability
       canOpenDetail - room 상세를 열 수 있는지
       canJoin - 현재 사용자가 참여할 수 있는지
       canEnterChat - 현재 사용자가 채팅 화면에 진입할 수 있는지
       canSendMessage - 현재 사용자가 메시지를 보낼 수 있는지
       canSubscribe - 현재 connection이 room event를 구독할 수 있는지

     ClientParticipationCapability
       canReadMessages - 메시지를 조회할 수 있는지
       canWriteMessages - 메시지를 전송할 수 있는지
       canSubscribeRoom - room event를 구독할 수 있는지
       canLeaveRoom - room에서 나갈 수 있는지

     ClientMessageAccessScope
       currentParticipationOnly - 현재 참여 구간 기준으로 메시지를 다뤄야 하는지
       canContinuePreviousMessages - 재입장 후 이전 참여 구간 메시지를 현재 메시지로 이어 보지 않아야 하는지
```

서버가 room 없음, CLOSED, 접근 불가를 명확히 응답한 경우에는 해당 room을 사용할 수 없는 흐름으로 보정한다.

네트워크 오류나 일시적인 query 실패만으로 기존 room 상태를 즉시 `UNAVAILABLE`로 바꾸지 않는다.

#### 인터랙션 영향

참여 전이면 join 행동을 유도한다.

참여 중이면 채팅 화면 진입을 허용할 수 있다.

사용할 수 없는 room이면 메시지 입력, 수정, 삭제, 구독 유지 동작을 중단한다.

재입장 후에는 `ClientMessageAccessScope`를 기준으로 이전 참여 구간 메시지를 현재 메시지처럼 이어 보지 않도록 한다.

---

## 6. 메시지 Query

### 6.1 listMessages

#### 목적

현재 참여 구간에서 표시 가능한 메시지 목록을 가져온다.

#### 사용자 맥락

```text
사용자가 채팅 화면에 진입함
사용자가 이전 메시지를 더 불러옴
사용자가 최신 메시지를 다시 확인함
사용자가 재입장 후 현재 참여 구간 메시지를 조회함
```

#### 외부 상태

```text
MessagePage
```

#### 반영 대상

```text
직접 반영:
  ClientMessage[]

재계산 대상:
  ClientMessageCapability
```

#### 반영 기준

`MessagePage`는 `ClientMessage[]`에 직접 반영되고, 그 결과로 메시지 capability를 다시 계산한다.

```text
MessagePage
  -> ClientMessage[]
     MessageReadItem을 서버 확정 메시지로 반영한다.

     그대로 반영:
       messageId
       clientMessageId
       roomId
       sender
       senderReaderKey
       content
       roomSequence
       editVersion

     클라이언트 해석:
       displayState:
         visibility, editedAt, deletedAt을 기준으로
         SENT / EDITED / DELETED 중 하나로 해석한다.

       orderKey:
         서버가 확정한 roomSequence를 메시지 정렬 기준으로 사용한다.

       initialDisplayPosition:
         firstUnreadSequence가 있으면 첫 번째 안 읽은 메시지를 초기 표시 기준으로 사용한다.
         firstUnreadSequence가 없으면 최신 메시지 구간의 하단을 초기 표시 기준으로 사용한다.

     병합 기준:
       messageId가 같으면 기존 ClientMessage를 갱신한다.
       clientMessageId가 같은 PendingMessage가 있으면 서버 확정 메시지로 대체한다.
       editVersion은 오래된 query 결과나 늦게 도착한 event가 최신 메시지를 덮어쓰지 않도록 하는 기준이다.

  -> 재계산
     ClientMessageCapability
       canEdit - 메시지를 수정할 수 있는지
       canDelete - 메시지를 삭제할 수 있는지
```

`MessagePage.hasBefore`, `MessagePage.hasAfter`는 이전/이후 메시지 추가 조회 가능 여부를 알려주는 외부 상태다.

`MessagePage.firstUnreadSequence`는 채팅 화면 최초 진입 시 초기 표시 위치를 알려주는 외부 상태다.

방 목록의 `unreadCount`는 읽지 않은 메시지 수 badge를 위한 요약값이며, 클라이언트가 초기 조회 sequence를 직접 계산하는 근거로 사용하지 않는다.

`DELETED_BY_USER` 메시지는 일반 content를 노출하지 않는다.

최종 수정/삭제 가능 여부는 `editMessage`, `deleteMessage` command 결과로 서버가 확정한다.

#### 인터랙션 영향

메시지 조회 성공은 해당 room의 채팅 화면을 현재 참여 구간 기준으로 보정한다.

읽지 않은 메시지가 있으면 첫 번째 안 읽은 메시지부터 볼 수 있게 한다.

읽지 않은 메시지가 없으면 최신 메시지를 기준으로 채팅 화면을 연다.

빈 메시지 목록은 room이 사용할 수 없다는 뜻이 아니라, 현재 조회 범위에 표시할 메시지가 없다는 뜻이다.

조회 실패는 pending 메시지를 제거하는 근거가 아니다.

---

### 6.2 catchUpMessages

#### 목적

WebSocket 재연결 또는 누락 감지 후 특정 sequence 이후의 메시지를 복구한다.

`catchUpMessages`는 `listMessages`와 같은 `MessagePage`를 반환할 수 있지만, 사용자 메시지 탐색이 아니라 실시간 event 누락 복구를 위한 query다.

향후 복구에 메시지 외 상태가 필요해질 수 있으므로 `listMessages`의 사용 시나리오가 아니라 별도 query로 분리한다.

#### 사용자 맥락

```text
WebSocket이 재연결됨
server event 누락 가능성이 있음
클라이언트가 마지막으로 반영한 roomSequence 이후 상태를 확인해야 함
```

#### 외부 상태

```text
MessagePage
```

#### 반영 대상

```text
직접 반영:
  ClientMessage[]
  ClientRoomSubscription

재계산 대상:
  ClientMessageCapability
```

#### 반영 기준

`MessagePage`는 누락된 메시지와 room subscription 복구 상태에 반영된다.

`catchUpMessages`에서 `firstUnreadSequence`는 초기 표시 위치로 사용하지 않는다.

```text
MessagePage
  -> ClientMessage[]
     afterSequence 이후 메시지를 기준으로 누락된 메시지를 병합한다.
     messageId가 같으면 중복 추가하지 않고 기존 ClientMessage를 갱신한다.
     editVersion은 오래된 메시지 상태가 최신 상태를 덮어쓰지 않도록 하는 기준이다.
     displayState 해석은 listMessages와 같은 기준을 따른다.

  -> ClientRoomSubscription
     messages가 있으면 클라이언트가 반영한 가장 큰 roomSequence로 lastReceivedSequence를 보정할 수 있다.
     messages가 없으면 lastReceivedSequence와 catchUpRequired를 변경하지 않는다.

  -> 재계산
     ClientMessageCapability
       canEdit - 메시지를 수정할 수 있는지
       canDelete - 메시지를 삭제할 수 있는지
```

#### 인터랙션 영향

catch-up 성공 후 채팅 화면은 서버가 확정한 메시지 순서와 상태를 기준으로 보정된다.

catch-up 실패는 실시간 연결 상태를 불안정하게 표시할 수 있지만, 기존 메시지 화면을 즉시 폐기하는 근거는 아니다.

---

## 7. 읽음 Query

### 7.1 getUnreadCounts

#### 목적

현재 사용자 기준으로 여러 room의 room unread count를 가져온다.

#### 사용자 맥락

```text
사용자가 room 목록을 보고 있음
사용자가 앱을 foreground로 전환함
read cursor 변경 후 목록 badge를 보정해야 함
server event 유실 가능성이 있어 unread count를 다시 맞춰야 함
```

#### 요청 범위

```text
roomIds optional
```

`roomIds`는 조회 범위를 제한하기 위한 요청 필드다.

`roomIds`가 없으면 현재 사용자가 참여 중인 room들의 room unread count를 조회할 수 있다.

현재 사용자 식별자는 요청 필드로 보내지 않고 인증 정보에서 결정된다.

#### 외부 상태

```text
UnreadCountSummary[]
```

#### 반영 대상

```text
직접 반영:
  ClientRoomSummary.unreadCount
```

#### 반영 기준

`UnreadCountSummary`는 `roomId`를 기준으로 반영한다.

`UnreadCountSummary.roomId`는 응답의 `unreadCount`를 어느 room 목록 항목에 반영할지 식별하기 위한 값이다.

```text
UnreadCountSummary[]
  -> ClientRoomSummary.unreadCount
     같은 roomId의 ClientRoomSummary가 있으면 unreadCount를 갱신한다.
```

`getUnreadCounts`는 read session을 생성하거나 변경하지 않는다.

read session 신호는 WebSocket command 경로에서 처리하고, unread count query는 표시값 보정만 담당한다.

#### 인터랙션 영향

room 목록에서 같은 `roomId`의 안 읽은 메시지 수 표시를 최신 서버 값으로 갱신한다.

---

### 7.2 getReadCursorSnapshot

#### 목적

room의 메시지별 read receipt count를 계산하기 위한 reader cursor snapshot을 가져온다.

#### 사용자 맥락

```text
사용자가 채팅 화면에 진입함
READ_CURSOR_UPDATED event가 유실되었거나 중복 반영되었을 수 있음
WebSocket reconnect 이후 reader cursor snapshot을 보정해야 함
메시지별 읽음 표시 계산 기준을 다시 맞춰야 함
```

#### 외부 상태

```text
ReadCursorSnapshot
```

#### 반영 대상

```text
직접 반영:
  ClientReadCursorSnapshot

재계산 대상:
  ClientReadReceipt
```

#### 반영 기준

`ReadCursorSnapshot`은 room의 기존 reader cursor snapshot을 교체한다.

기존 snapshot과 merge하지 않는다.

```text
ReadCursorSnapshot
  -> ClientReadCursorSnapshot
     같은 roomId의 기존 snapshot을 서버 snapshot으로 교체한다.

  -> ClientReadReceipt
     현재 표시 중인 메시지의 senderReaderKey와 snapshot.readers를 기준으로 readReceiptCount와 unreadReceiptCount를 다시 계산한다.
```

snapshot은 조회 시점의 ACTIVE member만 포함한다.

작성자가 snapshot에 포함되어 있으면 senderReaderKey 기준으로 작성자 본인을 계산에서 제외한다.

작성자가 이미 LEFT 상태라 snapshot에 없으면 별도 제외 처리는 필요 없다.

`readerKey`는 UI에 노출하지 않는다.

`getReadCursorSnapshot`은 read session을 생성하거나 변경하지 않는다.

#### 인터랙션 영향

현재 표시 중인 메시지 옆의 읽은 사람 수 또는 안 읽은 사람 수 표시를 다시 계산한다.

snapshot 조회 실패는 메시지 전송 성공 여부나 메시지 표시 여부를 바꾸지 않는다.
