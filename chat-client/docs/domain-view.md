# 오픈 채팅방 클라이언트 도메인 관점

## 1. 목적

이 문서는 오픈 채팅방의 비즈니스 핵심 요소를 클라이언트가 어떤 화면 의미로 해석하는지 정의한다.

클라이언트는 서버의 source of truth를 소유하지 않는다.

클라이언트는 외부 상태와 사용자 맥락을 함께 사용해 비즈니스 상태를 만들고, 비즈니스 상태를 다시 인터랙션 상태로 변환해 사용자가 이해할 수 있는 화면을 만든다.

---

## 2. 도메인 경계

클라이언트 도메인 경계는 외부에서 확정된 도메인 상태를 사용자 흐름과 interaction으로 해석하는 지점에서 생긴다.

서버는 room, membership, message 같은 도메인 상태를 확정해 전달하지만, 사용자는 그 값을 직접 읽는 것이 아니라 화면의 흐름과 가능한 행동으로 경험한다.

사용자는 현재 자신이 어떤 흐름에 있고, 무엇을 할 수 있으며, 다음에 무엇을 해야 하는지를 기대한다.

따라서 클라이언트는 외부 상태와 사용자 맥락을 함께 사용해 비즈니스 상태를 만들고, 비즈니스 상태를 다시 인터랙션 상태로 변환한다.

```text
[외부 상태]
        \
         -> [비즈니스 상태] -> [인터랙션 상태]
        /
[사용자 맥락]

외부 상태:
  클라이언트 밖에서 확정되어 전달된 도메인 상태다.
  이 문서에서 외부 상태는 query 결과, command 응답, server event를 의미한다.
  클라이언트는 외부 상태를 임의로 진실로 바꾸지 않는다.

사용자 맥락:
  사용자가 현재 보고 있는 화면, 입력 중인 값, 스크롤 위치,
  foreground/background 여부, 방금 실행한 행동처럼
  클라이언트가 관찰할 수 있는 사용 상황이다.

비즈니스 상태:
  외부 상태와 사용자 맥락을 조합해 사용자 흐름으로 해석한 상태다.
  사용자가 지금 어떤 단계에 있는지, 그 단계가 사용 가능한지 나타낸다.

인터랙션 상태:
  비즈니스 상태를 바탕으로 현재 화면에서 사용자의 행동을 제어하거나 유도하는 상태다.
  허용할 행동, 차단할 행동, 유도할 행동을 포함한다.
```

비즈니스 상태는 외부 상태에서 자동으로 파생되는 값이 아니다.

같은 외부 상태라도 사용자가 어떤 화면을 보고 있고 어떤 행동을 했는지에 따라 다른 비즈니스 상태로 해석될 수 있다.

이 문서는 비즈니스 핵심 요소별로 외부 상태와 사용자 맥락이 어떤 비즈니스 상태로 해석되고, 그 결과 어떤 인터랙션 상태로 이어지는지 설명한다.

진행 중인 요청은 비즈니스 상태나 인터랙션 상태에 영향을 주는 경우에만 포함한다.

---

## 3. 채팅방

채팅방은 사용자가 발견하고, 상세를 확인하고, 참여 여부를 결정하는 대상이다.

클라이언트에서 채팅방은 사용자가 대화에 접근하기 전 맥락을 이해하고 참여 여부를 결정하는 기준이다.

채팅방은 단순히 메시지 목록을 담는 container가 아니라, 사용자가 발견할 수 있는 대상이자 참여 의사를 표현할 수 있는 진입점이다.

클라이언트는 채팅방 정보를 통해 사용자가 어떤 room을 볼 수 있는지, 어떤 room에 들어갈 수 있는지, 어떤 room을 더 이상 사용할 수 없는지 해석한다.

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
```

### 3.2 사용자 맥락

```text
사용자가 room 목록을 보고 있음
사용자가 room 상세 화면을 보고 있음
사용자가 room 참여를 시도함
사용자가 stale한 room 화면에 머무름
```

### 3.3 비즈니스 상태

```text
ClientRoomSummary:
  roomId
  name
  operationStatus
  participantCount
  lastMessagePreview nullable
  unreadCount
  listVisibility

ClientRoomDetail:
  roomId
  name
  owner
  operationStatus
  participantCount
  participationStatus
  detailAvailability
  chatAvailability
  messageInputAvailability

방 목록 표시 여부:
  사용자가 room을 발견할 수 있는가

방 상세 사용 가능 여부:
  사용자가 room 정보를 보고 참여 여부를 판단할 수 있는가

채팅 화면 진입 가능 여부:
  현재 사용자가 room에서 메시지 조회/전송 흐름에 들어갈 수 있는가

메시지 입력 가능 여부:
  현재 사용자가 이 room에서 메시지를 보낼 수 있는가
```

### 3.4 인터랙션 상태

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

### 3.5 해석 규칙

방 목록에 보인다는 것은 채팅 화면을 사용할 수 있다는 뜻이 아니다.

방 상세를 볼 수 있다는 것도 현재 사용자가 참여 중이라는 뜻이 아니다.

채팅 화면 사용 가능 여부는 채팅방 정보와 현재 사용자의 참여 정보를 함께 기준으로 판단한다.

운영 개입 중인 room은 종료된 room과 다르게 해석한다.

운영 개입 중이어도 기존 참여자의 메시지 사용이 가능할 수 있다.

따라서 클라이언트는 운영 개입 상태를 곧바로 접근 불가로 해석하지 않는다.

---

## 4. 참여

참여는 현재 사용자가 특정 room에서 채팅 화면을 사용할 수 있는지 결정하는 기준이다.

클라이언트는 참여를 전체 참여자 목록이 아니라 현재 사용자와 특정 room 사이의 관계로 해석한다.

클라이언트에서 참여는 현재 사용자가 room 안에 있는지, 그리고 그 room의 채팅 화면을 사용할 수 있는지를 결정하는 기준이다.

참여는 단순한 권한 값이 아니라 메시지 조회 범위와 채팅 화면 사용 가능 여부를 함께 제한한다.

클라이언트는 참여 정보를 통해 참여 전, 참여 중, 나감, 재입장을 서로 다른 사용자 경험으로 해석한다.

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
사용자가 room 상세 화면을 보고 있음
사용자가 채팅 화면에 진입하려고 함
사용자가 room에서 나가려고 함
사용자가 나간 room에 다시 접근함
```

### 4.3 비즈니스 상태

```text
ClientParticipation:
  roomId
  status
  chatAvailability
  messageReadAvailability
  messageWriteAvailability

참여 가능 여부:
  room 상세에서 참여 버튼을 보여줄 수 있는가

채팅 화면 사용 가능 여부:
  메시지 조회, 메시지 전송, room 구독을 사용할 수 있는가

나간 room 처리:
  현재 채팅 화면을 닫고 이전 참여 구간 메시지를 현재 메시지로 이어 보지 않도록 할 것인가
```

### 4.4 인터랙션 상태

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

### 4.5 진행 중인 요청

```text
joinRoom 요청 중:
  참여 버튼과 채팅 화면 진입을 pending으로 표현한다.

leaveRoom 요청 중:
  채팅 화면 이탈과 구독 정리를 pending으로 표현한다.
```

### 4.6 해석 규칙

재입장 후 메시지 조회 범위는 서버가 확정한 현재 참여 구간을 따른다.

클라이언트는 모든 room에 대해 참여 전 상태를 미리 만들 필요는 없다.

참여 정보는 room 상세 조회, 참여 command 응답, server event를 통해 필요한 room에 대해서만 해석한다.

---

## 5. 메시지

메시지는 서버에서 확인한 메시지와 서버 확정 전 임시 메시지로 나뉜다.

클라이언트에서 메시지는 대화 맥락을 구성하는 단위이면서 사용자의 전송, 수정, 삭제 행동을 연결하는 대상이다.

메시지는 전송 중, 전송됨, 수정됨, 삭제됨 같은 사용자 인지 상태를 함께 표현한다.

메시지는 서버에 저장된 record를 보여주는 것에 그치지 않고, 사용자가 지금 보낸 말이 확정되었는지, 실패했는지, 수정 또는 삭제되었는지 이해하게 만드는 기준이다.

클라이언트는 메시지를 통해 대화의 순서, 표시 가능 여부, 수정/삭제 가능 여부, 읽음 표시를 함께 해석한다.

클라이언트는 메시지를 두 모델로 해석한다.

서버에서 확인한 메시지와 서버 확정 전 메시지는 식별자, 순서, 실패 처리 기준이 다르기 때문이다.

### 5.1 외부 상태

```text
MessagePage:
  roomId
  messages: MessageReadItem[]
  hasBefore
  hasAfter

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

MESSAGE_SENT event
MESSAGE_EDITED event
MESSAGE_DELETED_BY_USER event
```

### 5.2 사용자 맥락

```text
사용자가 채팅 화면을 보고 있음
사용자가 메시지를 입력 중임
사용자가 메시지 전송을 실행함
사용자가 메시지 수정 또는 삭제를 시도함
사용자가 전송 실패 메시지를 다시 보내려고 함
```

### 5.3 진행 중인 요청

메시지 전송 요청은 서버 확정 전에도 화면에 표시되어야 하므로 별도 모델로 다룬다.

```text
PendingMessage:
  clientMessageId
  roomId
  sender
  status
  content
  createdAt
```

### 5.4 비즈니스 상태

```text
ClientMessage:
  messageId optional
  clientMessageId optional
  roomId
  sender
  status
  content nullable
  displayState
  orderKey
  editVersion optional
  unreadReceiptCount optional

표시할 메시지:
  MessageReadItem과 PendingMessage를 함께 고려한 채팅 화면 메시지

메시지 순서:
  서버가 확정한 room 단위 메시지 순서를 최종 기준으로 사용한다.

메시지 수정 가능 여부:
  작성자, 참여 상태, editVersion, 서버 command 결과를 함께 기준으로 판단한다.

메시지 삭제 가능 여부:
  작성자, 참여 상태, editVersion, 서버 command 결과를 함께 기준으로 판단한다.
```

### 5.5 인터랙션 상태

```text
허용:
  sendMessage 요청
  editMessage 요청
  deleteMessage 요청
  failed message 재시도

차단:
  삭제된 메시지 수정
  현재 참여 구간 밖 메시지 수정/삭제
  오래된 editVersion 기반 수정/삭제

유도:
  전송 실패 메시지는 재시도를 유도한다.
  삭제된 메시지는 일반 content가 아니라 삭제 표시로 이해되게 한다.
```

### 5.6 해석 규칙

클라이언트는 같은 메시지가 여러 경로로 도착해도 하나의 메시지로 병합해야 한다.

서버 확정 메시지가 도착하면 같은 `clientMessageId`를 가진 임시 메시지를 서버 확정 메시지로 대체한다.

수정/삭제 이벤트가 늦게 도착할 수 있으므로 클라이언트는 메시지 버전을 기준으로 오래된 변경을 무시해야 한다.

삭제된 메시지는 content를 일반 메시지처럼 노출하지 않는다.

삭제 메시지를 placeholder로 표시할지 목록에서 제외할지는 UI 정책에서 정한다.

---

## 6. 읽음과 표시

읽음은 사용자가 특정 room에서 어디까지 읽었는지를 나타내는 기준이다.

클라이언트에서 읽음은 사용자가 대화를 어디까지 확인했는지와 내가 보낸 메시지가 다른 참여자에게 얼마나 도달했는지를 표현하는 기준이다.

읽음은 메시지 내용 자체가 아니라 room과 사용자 사이의 진행 상태를 화면에 드러내는 정보다.

클라이언트는 읽음 정보를 통해 room별 unread count와 메시지별 unread receipt count를 사용자가 이해할 수 있는 표시로 해석한다.

### 6.1 외부 상태

```text
UnreadCountSummary:
  roomId
  unreadCount

ReadReceiptSummary:
  messageId
  roomSequence
  readReceiptCount
  unreadReceiptCount

READ_CURSOR_UPDATED event
READ_RECEIPT_UPDATED event
```

### 6.2 사용자 맥락

```text
사용자가 room 화면을 보고 있음
사용자가 scroll bottom에 있음
사용자가 앱을 foreground로 전환함
사용자가 room을 이탈함
```

### 6.3 비즈니스 상태

```text
ClientReadState:
  roomId
  userId
  readCursor
  readSessionStatus
  unreadCount

ClientReadReceipt:
  messageId
  unreadReceiptCount

read cursor:
  사용자가 특정 room에서 읽은 마지막 메시지 위치

unread count:
  room 단위로 아직 읽지 않은 메시지 수

unread receipt count:
  메시지 단위로 아직 읽지 않은 대상 참여자 수
```

### 6.4 인터랙션 상태

```text
허용:
  markRead 보정 요청
  read session 활성화
  read session 비활성화

차단:
  read cursor 역행 반영
  서버가 확정하지 않은 unread count를 최종값처럼 표시

유도:
  room 진입, foreground, scroll-to-bottom, room leave 시점에 읽음 보정을 유도한다.
  unread badge와 unread receipt 표시로 읽음 상태를 인지하게 한다.
```

### 6.5 읽음 보정 신호

```text
read session:
  사용자가 채팅 화면을 보고 있음을 서버에 알리는 런타임 정보

markRead:
  클라이언트가 적절한 시점에 보내는 read cursor 보정 요청
```

### 6.6 해석 규칙

read cursor의 최종 값은 서버가 확정한다.

클라이언트는 새 메시지마다 읽음 갱신 요청을 보내지 않는다.

대신 room enter, foreground, scroll-to-bottom, room leave 같은 적절한 시점에 read cursor 보정을 요청한다.

읽음 관련 실시간 이벤트가 유실되어도 서버 조회 결과로 다시 계산할 수 있어야 한다.
