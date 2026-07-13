# 오픈 채팅방 클라이언트 Screen Model

## 1. 목적

이 문서는 프론트엔드 도메인 모델을 실제 화면 단위로 어떻게 조합하는지 정의한다.

이 문서는 각 화면이 어떤 외부 상태를 가져오고, 어떤 프론트엔드 도메인 모델을 사용하며, 어떤 command와 capability로 사용자 행동을 제어하는지 정리한다.

이 문서는 구현자와 디자이너가 같은 화면 상태와 행동 규칙을 기준으로 화면을 설계할 수 있도록 한다.

---

## 2. 화면 목록

초기 클라이언트는 다음 3개 화면을 기준으로 설계한다.

```text
내가 참여한 방 목록 화면
참여 가능한 방 조회 화면
채팅 화면
```

방 상세 정보는 별도 페이지로 분리하지 않고, 목록의 확장 영역, modal, popup 같은 화면 요소로 표현할 수 있다.

---

## 3. 공통 화면 처리 원칙

### 3.1 화면은 도메인 모델과 capability를 조합한다

화면은 서버 응답과 사용자 맥락을 조합해 현재 화면에서 필요한 상태와 행동 가능 여부를 결정한다.

화면은 프론트엔드 도메인 모델과 capability를 조합해 표시할 정보와 허용할 행동을 결정한다.

```text
query result / command response / server event
  -> 프론트엔드 도메인 모델
  -> capability
  -> 화면 표시와 사용자 행동
```

### 3.2 loading, empty, error는 화면 상태다

loading, empty, error는 서버 도메인 상태가 아니다.

화면은 일시적인 query 실패나 네트워크 오류만으로 room을 즉시 사용할 수 없는 상태로 확정하지 않는다.

서버가 room 없음, CLOSED, 접근 불가를 명확히 응답한 경우에만 사용할 수 없는 흐름으로 전환한다.

### 3.3 pending 상태는 화면에서 사용자 행동으로 드러난다

pending 상태는 사용자가 실행한 행동이 아직 서버에서 확정되지 않았음을 표현한다.

pending 모델 자체는 command와 domain-view 기준을 따르며, Screen Model에서는 각 화면에서 pending 상태가 어떻게 표시되고 어떤 행동을 제한하는지 다룬다.

```text
PendingMessage
PendingParticipationAction
ClientRoomSubscription.status = SUBSCRIBING | UNSUBSCRIBING
ClientRealtimeConnection.status = CONNECTING | RECONNECTING
```

### 3.4 표시 규칙은 의미를 정의한다

Screen Model의 표시 규칙은 색상, 간격, 위치 같은 시각 디자인을 정의하지 않는다.

이 문서는 화면에 드러나야 하는 정보, 상태의 의미, 사용자가 할 수 있는 행동을 정의한다.

```text
정의하는 것:
  어떤 정보를 보여주는가
  어떤 상태를 구분해야 하는가
  어떤 행동을 허용하거나 막는가

정의하지 않는 것:
  색상
  폰트
  spacing
  컴포넌트 트리
  반응형 breakpoint
```

---

## 4. 내가 참여한 방 목록 화면

### 4.1 역할

내가 참여한 방 목록 화면은 현재 사용자가 대화를 이어갈 room을 선택하는 화면이다.

이 화면은 room unread count와 last message preview를 통해 사용자가 어느 room을 먼저 확인할지 판단하게 한다.

### 4.2 사용하는 프론트엔드 도메인 모델

```text
ClientRoomSummary
ClientRoomCapability
ClientRealtimeConnection
```

### 4.3 사용하는 query

```text
listRooms
getUnreadCounts
```

`listRooms`는 목록에 표시할 `ClientRoomSummary`를 보정한다.

`getUnreadCounts`는 room unread count를 서버 값으로 보정한다.

방 목록 화면은 polling 또는 foreground 복귀 시점에 `listRooms` 또는 `getUnreadCounts`를 실행할 수 있다.

### 4.4 사용하는 command

```text
connectRealtime
```

`connectRealtime`은 전체 실시간 연결 상태를 준비하기 위해 사용한다.

### 4.5 capability

```text
ClientRoomCapability.canOpenDetail
ClientRoomCapability.canEnterChat
```

`canEnterChat`이 false이면 채팅 화면으로 진입시키지 않는다.

목록에 표시된 room이라고 해서 항상 채팅 화면에 진입할 수 있는 것은 아니다.

### 4.6 사용자 행동

```text
room 선택
참여 가능한 방 조회 화면으로 이동
```

### 4.7 화면 상태와 행동 기준

```text
initial
  - 아직 목록 조회를 시작하지 않은 상태
  - 목록 조회 시작

loading
  - 첫 목록 조회 중인 상태
  - room 선택 제한
  - 참여 가능한 방 조회 화면 이동 가능

ready
  - room 목록을 표시할 수 있는 상태
  - room 선택 가능
  - 참여 가능한 방 조회 화면 이동 가능

refreshing
  - 목록을 다시 조회 중인 상태
  - 기존 room 선택 가능
  - 중복 새로고침 제한
  - 참여 가능한 방 조회 화면 이동 가능

error
  - 목록 조회 실패 상태
  - 목록 재조회 가능
  - 참여 가능한 방 조회 화면 이동 가능

empty
  - 현재 사용자가 참여 중인 room이 없는 상태
  - 참여 가능한 방 조회 화면 이동 유도
```

### 4.8 표시 정보

내가 참여한 방 목록 item은 다음 정보를 우선순위에 따라 표시한다.

```text
1. room name
2. lastMessagePreview
3. room unread count
4. participantCount
```

### 4.9 표시 규칙

`room unread count`가 0이면 unread badge를 강조하지 않는다.

`lastMessagePreview`가 없으면 최근 표시 가능한 메시지가 없는 room으로 표시한다.

`SYSTEM_MANAGED`는 종료 상태가 아니라 운영 개입 상태로 구분해 표시한다.

---

## 5. 참여 가능한 방 조회 화면

### 5.1 역할

참여 가능한 방 조회 화면은 사용자가 새로 참여할 room을 발견하고 참여 의사를 표현하는 화면이다.

이 화면은 room 상세 맥락을 보여주고, 참여 가능한 상태인지 판단하게 한다.

방 상세 정보는 별도 페이지로 이동하지 않고, 참여 가능한 방 조회 화면 안에서 modal 또는 popover로 표시한다.

### 5.2 사용하는 프론트엔드 도메인 모델

```text
ClientRoomSummary
ClientRoomDetail
ClientParticipation
PendingParticipationAction
ClientRoomCapability
ClientParticipationCapability
```

### 5.3 사용하는 query

```text
listRooms
getRoomDetail
```

`listRooms`는 사용자가 발견 가능한 room 목록을 보정한다.

`getRoomDetail`은 참여 전 room 상세와 현재 사용자 참여 상태를 보정한다.

### 5.4 사용하는 command

```text
joinRoom
```

`joinRoom` 실행 중에는 `PendingParticipationAction(action = JOIN)`으로 중복 참여 요청을 막는다.

### 5.5 capability

```text
ClientRoomCapability.canOpenDetail
ClientRoomCapability.canJoin
ClientRoomCapability.canEnterChat
ClientParticipationCapability.canReadMessages
```

`canJoin`이 false이면 참여 행동을 차단한다.

`joinRoom` 성공 후 `canEnterChat`이 true이면 채팅 화면 진입을 유도할 수 있다.

### 5.6 사용자 행동

```text
room 목록 탐색
room 상세 확인
joinRoom 실행
join 실패 후 재시도
채팅 화면으로 이동
```

### 5.7 화면 상태와 행동 기준

```text
initial
  - 아직 참여 가능한 room 조회를 시작하지 않은 상태
  - 목록 조회 시작

loading
  - 참여 가능한 room 목록 또는 room 상세 조회 중인 상태
  - room 상세 확인 제한
  - joinRoom 실행 제한

ready
  - 참여 가능한 room 목록 또는 room 상세를 표시할 수 있는 상태
  - room 상세 확인 가능
  - canJoin이 true이면 joinRoom 실행 가능

joining
  - joinRoom 요청이 서버에서 확정되기 전 상태
  - 중복 joinRoom 실행 제한
  - room 상세 확인 가능

joinFailed
  - joinRoom 요청이 실패한 상태
  - joinRoom 재시도 가능
  - room 상세 확인 가능

error
  - 목록 또는 상세 조회 실패 상태
  - 재조회 가능

empty
  - 현재 조회 조건에서 새로 참여할 수 있는 room이 없는 상태
  - 재조회 가능
```

### 5.8 표시 정보

참여 가능한 방 목록 item 또는 상세 영역은 다음 정보를 우선순위에 따라 표시한다.

```text
1. room name
2. participantCount
3. operationStatus
4. currentUserParticipationStatus
5. join 가능 여부
```

방 상세 정보는 사용자가 참여 여부를 판단할 수 있을 만큼의 맥락을 제공한다.

방 상세 modal 또는 popover는 사용자가 참여 여부를 판단하는 데 필요한 정보만 표시한다.

참여 가능한 방 조회 화면에서 `room unread count`는 사용자 의사결정 정보로 사용하지 않는다.

참여 가능한 방 조회 화면에서는 `lastMessagePreview`를 표시하지 않는다.

참여 전 메시지 내용 또는 메시지 preview는 노출하지 않는다.

### 5.9 표시 규칙

`canJoin`이 false이면 참여 버튼을 실행 가능한 행동으로 표시하지 않는다.

`PendingParticipationAction(action = JOIN, status = PENDING)`이면 참여 버튼 중복 실행을 막고 joining 상태를 표시한다.

`PendingParticipationAction(action = JOIN, status = FAILED)`이면 참여 실패 상태와 재시도 가능성을 표시한다.

`SYSTEM_MANAGED`는 종료 상태가 아니라 운영 개입 상태로 구분해 표시한다.

서비스 정책상 신규 참여가 제한된 room이면 참여 불가 상태로 표시한다.

방 상세에서 참여 가능하면 join 행동을 제공하고, 참여 불가하면 참여할 수 없는 상태만 표시한다.

참여 성공 후에는 채팅 화면 진입을 유도한다.

---

## 6. 채팅 화면

### 6.1 역할

채팅 화면은 현재 참여 중인 room의 메시지를 조회하고, 메시지를 보내고, 읽음 상태를 표시하는 화면이다.

이 화면은 서버 확정 메시지, pending 메시지, 실시간 연결 상태, read cursor snapshot을 함께 조합한다.

### 6.2 사용하는 프론트엔드 도메인 모델

```text
ClientRoomDetail
ClientParticipation
ClientMessageAccessScope
ClientMessage
PendingMessage
ClientMessageCapability
ClientReadState
ClientReadCursorSnapshot
ClientMessageReadReceipt
ClientReadCapability
ClientRealtimeConnection
ClientRoomSubscription
ClientReadSession
ClientRealtimeConnectionCapability
ClientRoomRealtimeCapability
```

### 6.3 사용하는 query

```text
getRoomDetail
listMessages
getReadCursorSnapshot
catchUpMessages
```

`getRoomDetail`은 room 사용 가능 여부와 현재 사용자 참여 상태를 보정한다.

`listMessages`는 현재 참여 구간에서 표시 가능한 메시지를 보정한다.

`getReadCursorSnapshot`은 메시지별 message read receipt count 계산 기준을 보정한다.

`catchUpMessages`는 WebSocket event 누락 가능성이 있을 때 메시지 상태를 보정한다.

### 6.4 사용하는 command

```text
connectRealtime
subscribeRoom
unsubscribeRoom
activateReadSession
deactivateReadSession
markRead
sendMessage
editMessage
deleteMessage
leaveRoom
```

### 6.5 capability

```text
ClientRoomCapability.canSendMessage
ClientParticipationCapability.canReadMessages
ClientParticipationCapability.canWriteMessages
ClientParticipationCapability.canLeaveRoom
ClientMessageCapability.canEdit
ClientMessageCapability.canDelete
ClientReadCapability.canMarkRead
ClientReadCapability.canShowMessageReadReceiptCount
ClientRoomRealtimeCapability.canSubscribe
ClientRoomRealtimeCapability.canCatchUp
ClientRoomRealtimeCapability.canSendReadSessionSignal
```

capability는 버튼, 입력창, 메시지 action, read session 신호, catch-up 실행 가능 여부를 제어한다.

최종 성공/실패는 서버 command response를 따른다.

### 6.6 사용자 행동

```text
메시지 전송
전송 실패 메시지 재시도
메시지 수정
메시지 삭제
이전 메시지 더 보기
room 나가기
채팅 화면 이탈
```

### 6.7 화면 상태와 행동 기준

```text
initial
  - 아직 채팅 화면 초기 조회를 시작하지 않은 상태
  - room 상세, 메시지, 읽음 snapshot 조회 시작

loading
  - 채팅 화면 초기 조회 중인 상태
  - 메시지 입력 제한
  - 메시지 action 제한

ready
  - 메시지 목록과 room 상태를 표시할 수 있는 상태
  - canWriteMessages가 true이면 메시지 전송 가능
  - 메시지별 capability가 true이면 수정/삭제 가능
  - 이전 메시지 더 보기 가능

refreshing
  - 기존 채팅 화면을 유지한 채 일부 상태를 다시 조회 중인 상태
  - 기존 메시지 확인 가능
  - 중복 새로고침 제한

reconnecting
  - WebSocket 재연결 중인 상태
  - 실시간 수신 지연 표시
  - sendMessage는 WebSocket 연결 끊김만으로 차단하지 않음

catchingUp
  - 누락된 message event를 query로 복구 중인 상태
  - 기존 메시지 확인 가능
  - catchUpMessages 중복 실행 제한

stale
  - 실시간 이벤트 누락 가능성이 있어 query 보정이 필요한 상태
  - catchUpMessages 실행 가능

unavailable
  - 서버가 room 없음, CLOSED, 접근 불가를 명확히 응답한 상태
  - 메시지 입력 제한
  - room 구독 유지 제한
  - 채팅 화면 이탈 유도

error
  - 채팅 화면 조회 또는 보정 요청 실패 상태
  - 재조회 가능
  - 기존 메시지가 있으면 확인 가능

empty
  - 현재 참여 구간에서 표시할 메시지가 없는 상태
  - 메시지 전송 가능 여부는 capability를 따름
```

### 6.8 표시 정보

채팅 화면 상단은 다음 정보를 우선순위에 따라 표시한다.

```text
1. room name
2. connection status
3. catch-up status
4. leave action
```

대화 흐름 영역은 메시지를 순서 기준으로 표시한다.

```text
표시 대상:
  서버 확정 메시지
  전송 중 메시지
  전송 실패 메시지

정렬 기준:
  서버 확정 메시지는 roomSequence 기준으로 표시한다.
  전송 중 메시지는 클라이언트 생성 시점 기준으로 임시 위치에 표시한다.
  서버 확정 메시지가 도착하면 전송 중 메시지를 서버 확정 메시지로 대체하고 roomSequence 기준 위치로 보정한다.
```

메시지 말풍선은 다음 정보를 우선순위에 따라 표시한다.

```text
1. content
2. sender
3. sentAt
4. displayState
5. message read receipt count
6. derivedUnreadReceiptCount optional
```

메시지 입력 영역은 다음 정보를 표시한다.

```text
1. message input availability
```

메시지 입력 영역은 입력 가능, 전송 중, 전송 불가 상태를 구분해 표시한다.

전송 불가 상태에서는 메시지 입력 또는 전송 행동을 막는다.

### 6.9 메시지 표시 규칙

`PendingMessage.status = SENDING`이면 서버 확정 전 전송 중 메시지로 표시한다.

`PendingMessage.status = RETRYING`이면 재시도 중 메시지로 표시한다.

`PendingMessage.status = FAILED`이면 재시도 가능한 전송 실패 메시지로 표시한다.

현재 사용자가 보낸 메시지는 오른쪽 말풍선으로 표시한다.

다른 사용자가 보낸 메시지는 왼쪽 말풍선으로 표시한다.

pending message와 failed message는 현재 사용자가 보낸 메시지이므로 오른쪽 말풍선으로 표시한다.

`ClientMessage.displayState = SENT`이면 일반 메시지로 표시한다.

`ClientMessage.displayState = EDITED`이면 수정된 메시지임을 구분할 수 있어야 한다.

`ClientMessage.displayState = DELETED`이면 메시지 content와 placeholder를 표시하지 않는다.

`ClientMessage`는 `roomSequence` 기준 순서로 표시한다.

같은 메시지가 command response, server event, query 결과로 중복 도착해도 하나의 메시지로 병합해 표시한다.

### 6.10 읽음 표시 규칙

message read receipt count는 메시지 말풍선에 표시할 수 있다.

message read receipt count 계산 기준이 준비되지 않았으면 읽음 표시 영역에 loading 상태를 표시한다.

읽음 표시 준비 중 상태는 메시지 본문 표시를 막지 않는다.

읽음 표시가 준비되면 메시지 말풍선의 읽음 표시 영역만 갱신한다.

작성자 본인은 제외된 값으로 표시한다.

`readerKey`는 UI에 노출하지 않는다.

read-by 사용자 목록 노출은 초기 범위에 포함하지 않는다.

### 6.11 연결 및 동기화 표시 규칙

`ClientRealtimeConnection.status = RECONNECTING`이면 실시간 반영이 지연될 수 있음을 표시한다.

`ClientRoomSubscription.catchUpRequired = true`이면 최신 메시지 복구가 필요함을 표시할 수 있다.

`catchingUp` 상태에서도 기존 메시지 목록을 즉시 폐기하지 않는다.

WebSocket 연결 끊김만으로 `sendMessage`를 차단하지 않는다.

연결 끊김 중 전송한 메시지는 pending message 또는 failed message로 표시될 수 있다.

### 6.12 진입 처리

채팅 화면 진입 시 다음 상태를 준비해야 한다.

```text
room 사용 가능 여부
대화 흐름에 표시할 메시지
첫 번째 안 읽은 메시지 위치
message read receipt count 계산 기준
room event 구독 상태
read session 신호
```

`listMessages` 결과의 `firstUnreadSequence`는 초기 스크롤 위치를 결정한다.

첫 번째 안 읽은 메시지가 있으면 해당 메시지 근처를 초기 스크롤 위치로 사용한다.

첫 번째 안 읽은 메시지가 없으면 최신 메시지 근처를 초기 스크롤 위치로 사용한다.

`getReadCursorSnapshot`은 메시지별 read receipt count 계산 기준을 제공한다.

`activateReadSession`은 사용자가 room 화면을 보고 있음을 서버에 알리는 런타임 신호다.

### 6.13 room 나가기 처리

`leaveRoom` 성공 후에는 현재 채팅 화면을 계속 사용 가능한 상태로 두지 않는다.

`leaveRoom` 성공 후에는 내가 참여한 방 목록 화면 또는 참여 가능한 방 조회 화면으로 이동한다.

`leaveRoom` 실패 시에는 현재 채팅 화면을 유지하고 실패 상태를 표시할 수 있다.

### 6.14 이탈 처리

채팅 화면 이탈 시 다음 흐름을 수행한다.

```text
markRead
deactivateReadSession
unsubscribeRoom
```

`markRead`는 현재 사용자의 read cursor를 서버에 동기화하기 위한 보정 요청이다.

`deactivateReadSession`은 더 이상 room 화면을 보고 있지 않음을 서버에 알리는 런타임 신호다.

`unsubscribeRoom`은 해당 room의 server event 수신을 중단한다.
