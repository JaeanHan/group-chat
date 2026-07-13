# 오픈 채팅방 클라이언트 Commands

## 1. 목적

이 문서는 사용자의 쓰기 행동이 클라이언트 안에서 어떤 pending 상태를 만들고, 서버 확정 결과로 어떻게 보정되는지 정의한다.

클라이언트 command 처리는 API path나 request/response JSON 형식을 정의하지 않는다.

이 문서는 사용자 행동, capability 확인, pending 모델 생성, command response 보정, server event 병합 기준을 정의한다.

---

## 2. 공통 원칙

### 2.1 command 처리 흐름

```text
사용자 행동
  -> capability 확인
  -> pending 모델 생성 또는 runtime 상태 변경
  -> 서버 command 요청
  -> command response로 요청한 화면 보정
  -> server event 또는 query result로 다른 화면/connection 보정
```

command response는 해당 command를 실행한 connection에서 성공/실패를 판단하는 1차 기준이다.

server event는 같은 room을 보고 있는 다른 connection과 화면 상태를 동기화하기 위한 입력이다.

같은 변경이 command response와 server event로 모두 도착할 수 있으므로 클라이언트는 idempotent하게 병합해야 한다.

### 2.2 capability와 서버 확정

Capability는 사용자가 명백히 불가능한 행동을 시도하지 않도록 막기 위한 사전 판단이다.

최종 성공/실패는 서버 command response가 확정한다.

Capability가 허용하더라도 서버 command가 실패할 수 있다.

서버 command가 실패하면 클라이언트는 pending 상태를 실패로 보정하거나 기존 상태로 되돌린다.

### 2.3 요청 식별자

`clientRequestId`는 idempotency가 필요한 command 요청을 식별하기 위한 값이다.

`clientRequestId`는 UUID를 사용한다.

같은 사용자 행동을 재시도하는 경우 같은 `clientRequestId`를 유지할 수 있다.

`clientRequestId`는 화면 모델 식별자가 아니라 요청 중복 처리를 위한 값이다.

`clientMessageId`는 메시지 전송 요청과 `PendingMessage`를 연결하기 위한 값이다.

`clientMessageId`는 UUID를 사용한다.

서버 확정 메시지는 `clientMessageId`를 포함하며, 클라이언트는 같은 `clientMessageId`의 `PendingMessage`와 `ClientMessage`를 병합한다.

---

## 3. 메시지 Command

### 3.1 sendMessage

#### 사용자 행동

사용자가 현재 room에서 메시지 전송을 실행한다.

#### 실행 전 확인

```text
ClientRoomCapability.canSendMessage
ClientParticipationCapability.canWriteMessages
```

Capability가 허용하지 않으면 command 요청을 보내지 않고 입력 행동을 차단한다.

#### pending 모델

`sendMessage`를 실행하면 클라이언트는 `PendingMessage`를 생성한다.

```text
PendingMessage:
  clientMessageId
  roomId
  sender
  status = SENDING
  content
  createdAt
```

#### 성공 보정

```text
sendMessage command response:
  같은 clientMessageId의 PendingMessage를 제거한다.
  response의 서버 확정 메시지를 ClientMessage로 반영한다.

MESSAGE_SENT event:
  같은 messageId의 ClientMessage가 있으면 기존 ClientMessage를 보정한다.
  같은 clientMessageId의 PendingMessage가 있으면 ClientMessage로 대체한다.
  기존 ClientMessage와 PendingMessage가 모두 없으면 새 ClientMessage로 추가한다.
  이미 command response로 반영된 메시지이면 중복 추가하지 않는다.
```

`ClientMessage`는 서버 확정 메시지이므로 `messageId`, `roomSequence`, `editVersion`, `senderReaderKey`를 가진다.

#### 실패 보정

```text
sendMessage command failure:
  PendingMessage.status = FAILED
```

사용자는 실패한 `PendingMessage`를 재시도할 수 있다.

재시도 시 기존 `clientMessageId`를 유지하고 `PendingMessage.status = RETRYING`으로 보정한다.

#### 병합 기준

```text
1. messageId가 있으면 roomId + messageId 기준으로 병합한다.
2. PendingMessage와 서버 확정 메시지는 clientMessageId 기준으로 병합한다.
3. 화면 정렬은 서버가 확정한 roomSequence를 기준으로 한다.
```

### 3.2 editMessage

#### 사용자 행동

사용자가 자신이 보낸 메시지 수정을 실행한다.

#### 실행 전 확인

```text
ClientMessageCapability.canEdit
```

Capability가 허용하지 않으면 수정 행동을 차단한다.

#### pending 모델

초기 범위에서는 별도 pending edit 모델을 만들지 않고, command in-flight 상태로 수정 interaction만 제한한다.

#### 성공 보정

```text
editMessage command response:
  messageId 기준으로 ClientMessage를 보정한다.
  editVersion이 증가한 서버 확정 상태를 반영한다.

MESSAGE_EDITED event:
  messageId 기준으로 ClientMessage를 보정한다.
  현재 ClientMessage.editVersion보다 낮은 event는 무시한다.
```

#### 실패 보정

수정 실패 시 기존 `ClientMessage`를 유지한다.

서버가 editVersion 충돌을 응답하면 최신 메시지 조회 또는 event 결과로 보정한다.

### 3.3 deleteMessage

#### 사용자 행동

사용자가 자신이 보낸 메시지 삭제를 실행한다.

#### 실행 전 확인

```text
ClientMessageCapability.canDelete
```

Capability가 허용하지 않으면 삭제 행동을 차단한다.

#### pending 모델

초기 범위에서는 별도 pending delete 모델을 만들지 않고, command in-flight 상태로 삭제 interaction만 제한한다.

#### 성공 보정

```text
deleteMessage command response:
  messageId 기준으로 ClientMessage.displayState = DELETED로 보정한다.
  content는 표시하지 않는다.
  editVersion이 증가한 서버 확정 상태를 반영한다.

MESSAGE_DELETED_BY_USER event:
  messageId 기준으로 ClientMessage를 보정한다.
  현재 ClientMessage.editVersion보다 낮은 event는 무시한다.
```

#### 실패 보정

삭제 실패 시 기존 `ClientMessage`를 유지한다.

서버가 editVersion 충돌을 응답하면 최신 메시지 조회 또는 event 결과로 보정한다.

---

## 4. 참여 Command

### 4.1 joinRoom

#### 사용자 행동

사용자가 room 참여를 실행한다.

#### 실행 전 확인

```text
ClientRoomCapability.canJoin
```

Capability가 허용하지 않으면 join 요청을 보내지 않는다.

#### pending 모델

```text
PendingParticipationAction:
  roomId
  action = JOIN
  status = PENDING
  requestedAt
```

#### 성공 보정

```text
joinRoom command response:
  PendingParticipationAction(JOIN)을 제거한다.
  ClientParticipation.status = JOINED로 보정한다.
  ClientMessageAccessScope를 현재 참여 기준으로 보정한다.

ROOM_JOINED event:
  현재 사용자와 관련된 event이면 ClientParticipation 보정 입력으로 사용한다.
```

#### 실패 보정

```text
joinRoom command failure:
  PendingParticipationAction(JOIN).status = FAILED
  failedReason을 기록할 수 있다.
```

### 4.2 leaveRoom

#### 사용자 행동

사용자가 room 나가기를 실행한다.

#### 실행 전 확인

```text
ClientParticipationCapability.canLeaveRoom
```

Capability가 허용하지 않으면 leave 요청을 보내지 않는다.

#### pending 모델

```text
PendingParticipationAction:
  roomId
  action = LEAVE
  status = PENDING
  requestedAt
```

#### 성공 보정

```text
leaveRoom command response:
  PendingParticipationAction(LEAVE)을 제거한다.
  ClientParticipation.status = LEFT로 보정한다.
  ClientMessageAccessScope를 더 이상 현재 메시지를 이어 보지 않는 상태로 보정한다.
  ClientReadSession을 INACTIVE로 보정한다.
  ClientRoomSubscription은 구독 해제 대상이 된다.

ROOM_LEFT event:
  현재 사용자와 관련된 event이면 ClientParticipation 보정 입력으로 사용한다.
```

#### 실패 보정

```text
leaveRoom command failure:
  PendingParticipationAction(LEAVE).status = FAILED
  failedReason을 기록할 수 있다.
```

---

## 5. 읽음 Command

### 5.1 markRead

#### 사용자 행동

클라이언트가 현재 사용자가 특정 room에서 어디까지 읽었는지 서버에 동기화한다.

`markRead`는 새 메시지마다 호출하지 않는다.

room enter, foreground 복귀, scroll-to-bottom, room leave 같은 시점에 사용할 수 있다.

`markRead`는 readerKey 기반 cursor snapshot을 조회하는 command가 아니다.

readerKey 기반 snapshot은 `getReadCursorSnapshot`으로 가져오며, `markRead`는 현재 사용자의 read cursor를 서버에 확정 요청한다.

#### 실행 전 확인

```text
ClientReadCapability.canMarkRead
```

#### pending 모델

초기 범위에서 별도 pending read 모델은 만들지 않는다.

#### 성공 보정

```text
markRead command response:
  서버가 확정한 현재 사용자의 lastReadSequence를 받는다.
  ClientReadState.readCursor를 해당 값으로 보정한다.
  ClientReadCursorSnapshot이 있으면 currentUserReaderKey 항목의 lastReadSequence도 같은 값으로 보정할 수 있다.

READ_CURSOR_UPDATED event:
  readerKey 기준으로 ClientReadCursorSnapshot의 lastReadSequence를 보정한다.
  같은 readerKey에 대해 기존 값보다 작거나 같은 lastReadSequence는 무시한다.
```

`READ_CURSOR_UPDATED`는 room unread count를 직접 갱신하는 기준이 아니다.

room unread count는 `getUnreadCounts` 또는 room 목록 query 결과로 서버 값에 맞춰 보정한다.

#### 실패 보정

markRead 실패는 메시지 표시 자체를 실패로 만들지 않는다.

필요하면 다음 foreground, reconnect, scroll-to-bottom 시점에 다시 보정 요청한다.

### 5.2 activateReadSession

#### 사용자 행동

사용자가 room 화면을 보고 있음을 서버에 알린다.

#### 실행 전 확인

```text
ClientRoomRealtimeCapability.canSendReadSessionSignal
```

#### pending 모델

별도 pending 모델은 만들지 않는다.

#### 성공 보정

```text
activateReadSession command response:
  ClientReadSession.status = ACTIVE
```

read session은 읽음 데이터가 아니라 서버에 보내는 런타임 신호 상태다.

#### 실패 보정

실패 시 `ClientReadSession.status = INACTIVE`를 유지한다.

### 5.3 deactivateReadSession

#### 사용자 행동

사용자가 더 이상 room 화면을 보고 있지 않음을 서버에 알린다.

#### 실행 전 확인

```text
ClientRoomRealtimeCapability.canSendReadSessionSignal
```

#### pending 모델

별도 pending 모델은 만들지 않는다.

#### 성공 보정

```text
deactivateReadSession command response:
  ClientReadSession.status = INACTIVE
```

#### 실패 보정

deactivate 실패는 사용자 행동을 막는 오류로 다루지 않는다.

connection 종료, room 이탈, app background 전환 시 클라이언트 runtime은 `ClientReadSession.status = INACTIVE`로 정리할 수 있다.

---

## 6. 실시간 연결 Command

### 6.1 connectRealtime

#### 사용자 행동

클라이언트가 WebSocket 연결을 시작하거나 재연결을 시도한다.

#### 실행 전 확인

```text
ClientRealtimeConnectionCapability.canConnectRealtime
```

#### pending 모델

별도 pending 모델은 만들지 않는다.

연결 시도 중에는 `ClientRealtimeConnection.status = CONNECTING` 또는 `RECONNECTING`으로 보정한다.

#### 성공 보정

```text
connectRealtime command response:
  ClientRealtimeConnection.status = CONNECTED
  lastConnectedAt을 갱신한다.
```

연결 성공은 특정 room 구독 성공을 의미하지 않는다.

연결 성공 후 필요한 room은 `subscribeRoom`으로 다시 구독한다.

재연결 성공 후 `catchUpRequired`가 있는 room은 `catchUpMessages` 대상으로 남긴다.

#### 실패 보정

```text
connectRealtime command failure:
  ClientRealtimeConnection.status = DISCONNECTED
  disconnectedAt을 갱신한다.
```

인증 만료로 실패하면 `ClientRealtimeConnection.status = AUTH_EXPIRED`로 보정한다.

연결 실패는 room 자체가 사용할 수 없다는 뜻이 아니다.

### 6.2 subscribeRoom

#### 사용자 행동

클라이언트가 특정 room의 server event 수신을 시작한다.

#### 실행 전 확인

```text
ClientRoomRealtimeCapability.canSubscribe
```

#### pending 모델

```text
ClientRoomSubscription.status = SUBSCRIBING
```

#### 성공 보정

```text
subscribeRoom command response:
  ClientRoomSubscription.status = SUBSCRIBED
```

#### 실패 보정

```text
subscribeRoom command failure:
  ClientRoomSubscription.status = FAILED
```

구독 실패는 room 자체가 사용할 수 없다는 뜻이 아니다.

### 6.3 unsubscribeRoom

#### 사용자 행동

클라이언트가 특정 room의 server event 수신을 중단한다.

#### 실행 전 확인

현재 room에 대한 구독 상태가 존재해야 한다.

#### pending 모델

```text
ClientRoomSubscription.status = UNSUBSCRIBING
```

#### 성공 보정

```text
unsubscribeRoom command response:
  ClientRoomSubscription.status = UNSUBSCRIBED
```

#### 실패 보정

unsubscribe 실패는 사용자 행동을 막는 오류로 다루지 않는다.

room 화면 이탈이나 connection 종료 시 클라이언트 runtime은 해당 room subscription을 정리할 수 있다.

---

## 7. 실패와 재시도 기준

메시지 전송 실패는 사용자가 직접 복구할 수 있는 실패로 본다.

```text
PendingMessage.status = FAILED
```

참여 command 실패는 사용자가 다시 시도하거나 현재 room 상태를 재조회하도록 유도한다.

읽음 command와 read session command 실패는 화면의 메시지 표시를 실패 상태로 만들지 않는다.

실시간 연결 command 실패는 실시간 동기화 지연으로 표현하고, query 또는 catch-up으로 보정할 수 있어야 한다.
