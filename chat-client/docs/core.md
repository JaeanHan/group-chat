# 오픈 채팅방 클라이언트 Core

## 1. 목적

이 문서는 오픈 채팅방 비즈니스를 사용자 경험과 클라이언트 상태 관점에서 해석하는 최상위 기준을 정의한다.

클라이언트 Core는 사용자가 무엇을 볼 수 있어야 하는지, 무엇을 조작할 수 있어야 하는지, 서버 확정 전 상태를 어떻게 인지해야 하는지를 다룬다.

---

## 2. 범위

이 문서는 오픈 채팅방 클라이언트의 핵심 사용자 경험을 다룬다.

포함 범위:

```text
- 오픈 채팅방 목록 조회
- 오픈 채팅방 상세 조회
- 오픈 채팅방 참여
- 채팅방 화면 진입과 이탈
- 메시지 조회
- 메시지 전송
- 메시지 수정
- 메시지 삭제
- 읽음 상태 표시
- room unread count 표시
- message read receipt count 표시
- 실시간 연결과 재연결
- 전송 실패와 동기화 실패 표시
```

제외 범위:

```text
- 관리자 운영 화면
- 파일/이미지 업로드 UX
- 알림/푸시 정책
- 검색
- 신고/제재
- 테마와 디자인 시스템
```

---

## 3. Core 원칙

### 3.1 사용자 인지 상태를 기준으로 설계한다

클라이언트는 서버의 내부 상태를 그대로 노출하지 않는다.

같은 비즈니스 상태라도 사용자가 인지해야 하는 의미로 바꾸어 표현한다.

```text
room 종료:
  접근할 수 없는 room

메시지 저장 전:
  전송 중인 메시지

WebSocket 재연결:
  동기화가 필요한 상태

삭제된 메시지:
  말풍선 자체를 표시하지 않는 메시지
```

### 3.2 서버 확정 상태와 클라이언트 임시 상태를 분리한다

클라이언트는 사용자의 행동을 즉시 화면에 반영할 수 있다.

다만 서버에 확정된 상태와 클라이언트가 임시로 만든 상태는 구분해야 한다.

```text
server-confirmed:
  서버 조회 또는 서버 이벤트로 확정된 상태

client-temporary:
  서버 확정 전 화면 반응을 위해 만든 상태
```

임시 상태는 서버 확정 결과에 따라 확정, 보정, 실패 상태로 전환될 수 있어야 한다.

### 3.3 사용자가 할 수 있는 행동을 명확히 제한한다

클라이언트는 사용자가 현재 화면에서 가능한 행동과 불가능한 행동을 구분해 보여줘야 한다.

```text
- 참여 전에는 메시지를 보낼 수 없다.
- 나간 room에서는 메시지 화면을 계속 사용할 수 없다.
- 삭제된 메시지는 수정할 수 없다.
- 전송 실패 메시지는 재시도 또는 제거할 수 있어야 한다.
```

최종 권한 판단은 서버가 수행하지만, 클라이언트는 사용자가 명백히 불가능한 행동을 시도하지 않도록 화면 상태를 구성해야 한다.

### 3.4 실시간 이벤트를 최종 상태로 간주하지 않는다

WebSocket 이벤트는 화면을 빠르게 갱신하기 위한 입력이다.

클라이언트는 WebSocket 이벤트를 받으면 즉시 화면에 반영할 수 있다.

다만 WebSocket 이벤트는 중복, 지연, 유실될 수 있으므로 최종 상태로 간주하지 않는다.

메시지 목록, room unread count, message read receipt count는 서버 조회 결과 또는 누락 복구 결과로 다시 보정될 수 있어야 한다.

### 3.5 실패와 지연을 숨기지 않는다

클라이언트는 네트워크 지연, 전송 실패, 재연결, 동기화 중 상태를 사용자에게 이해 가능한 방식으로 표현한다.

사용자 행동 실패와 시스템 동기화 실패는 구분해서 다룬다.

```text
user action failure:
  메시지 전송 실패, 수정 실패, 삭제 실패

sync failure:
  재연결 실패, 누락 메시지 복구 실패, 읽음 상태 갱신 실패
```

---

## 4. 채팅방

채팅방은 사용자가 발견하고, 상세를 확인하고, 참여 여부를 결정하는 대상이다.

클라이언트는 서버 도메인 상태를 그대로 노출하지 않고, 사용자가 인지하는 화면 상태로 변환한다.

상태는 사용자 행동, 조회 결과, 실시간 이벤트, 네트워크 상태에 의해 전이된다.

### 4.1 접근 생명주기

접근 생명주기는 사용자가 room을 발견하고, 상세를 확인하고, 접근 가능 여부를 인지하는 흐름이다.

```text
NOT_DISCOVERED
  -- 방 목록에서 발견 -->
DISCOVERABLE

DISCOVERABLE
  -- 사용자가 room 선택 또는 상세 확인 -->
DETAIL_VISIBLE

DISCOVERABLE or DETAIL_VISIBLE
  -- room 종료 또는 접근 불가 확인 -->
UNAVAILABLE
```

`DISCOVERABLE`은 사용자가 목록에서 room을 발견할 수 있는 상태다.

`DETAIL_VISIBLE`은 사용자가 room 상세를 보고 참여 여부를 결정할 수 있는 상태다.

`UNAVAILABLE`은 room이 종료되었거나 사용자가 접근할 수 없는 상태다.

채팅방 접근 상태는 참여 상태와 다르다.

사용자가 room 상세를 볼 수 있어도 아직 메시지 화면을 사용할 수 있는 것은 아니다.

---

### 4.2 참여 생명주기

참여 상태는 사용자가 특정 채팅방에서 메시지 화면을 사용할 수 있는지 결정한다.

참여 생명주기는 사용자가 특정 room의 메시지 화면을 사용할 수 있는지 나타낸다.

```text
NOT_JOINED
  -- 사용자가 참여 선택 -->
JOIN_PENDING

JOIN_PENDING
  -- 참여 확정 -->
JOINED

JOIN_PENDING
  -- 참여 실패 -->
NOT_JOINED_WITH_ERROR

JOINED
  -- 사용자가 나가기 선택 -->
LEAVE_PENDING

LEAVE_PENDING
  -- 나가기 확정 -->
LEFT

LEAVE_PENDING
  -- 나가기 실패 -->
JOINED_WITH_ERROR

JOINED
  -- room 접근 불가 또는 참여 상태 상실 -->
UNAVAILABLE

LEFT
  -- 사용자가 재참여 선택 -->
JOIN_PENDING
```

`JOIN_PENDING`과 `LEAVE_PENDING`은 서버 확정 전 클라이언트 임시 상태다.

`JOINED`는 사용자가 메시지 화면을 사용할 수 있는 상태다.

`LEFT`는 사용자가 room을 나간 상태이며, 메시지 화면과 실시간 구독은 정리되어야 한다.

재입장한 사용자는 현재 참여 이후 메시지를 기준으로 화면을 구성해야 한다.

---

## 5. 메시지

메시지는 채팅방 화면의 중심 상태다.

클라이언트는 메시지를 전송, 수정, 삭제, 서버 메시지 병합 관점으로 나누어 해석한다.

### 5.1 전송 생명주기

전송 생명주기는 사용자가 작성한 메시지가 서버 확정 메시지로 바뀌는 흐름을 나타낸다.

```text
COMPOSING
  -- 사용자가 메시지 전송 -->
SENDING

SENDING
  -- 전송 확정 또는 서버 메시지와 병합 -->
SENT

SENDING
  -- 전송 실패 -->
FAILED

FAILED
  -- 사용자가 재전송 선택 -->
RETRYING

RETRYING
  -- 전송 확정 또는 서버 메시지와 병합 -->
SENT

RETRYING
  -- 전송 실패 -->
FAILED
```

`SENDING`과 `RETRYING`은 서버 확정 전 클라이언트 임시 상태다.

전송이 확정되면 클라이언트 임시 메시지는 서버 확정 메시지로 바뀐다.

전송이 실패하면 사용자는 재전송하거나 실패 메시지를 제거할 수 있어야 한다.

---

### 5.2 수정 생명주기

수정 생명주기는 서버에 확정된 메시지를 사용자가 수정할 때 화면에서 보이는 흐름을 나타낸다.

```text
SENT or EDITED
  -- 사용자가 메시지 수정 -->
EDITING

EDITING
  -- 수정 확정 또는 서버 수정 메시지와 병합 -->
EDITED

EDITING
  -- 수정 실패 -->
SENT_WITH_ERROR
```

`EDITING`은 수정 확정 전 클라이언트 임시 상태다.

수정이 실패하면 기존 메시지 상태를 유지하고 사용자에게 실패를 표시해야 한다.

---

### 5.3 삭제 생명주기

삭제 생명주기는 서버에 확정된 메시지를 사용자가 삭제할 때 화면에서 보이는 흐름을 나타낸다.

```text
SENT or EDITED
  -- 사용자가 메시지 삭제 -->
DELETING

DELETING
  -- 삭제 확정 또는 서버 삭제 메시지와 병합 -->
DELETED

DELETING
  -- 삭제 실패 -->
SENT_WITH_ERROR
```

`DELETING`은 삭제 확정 전 클라이언트 임시 상태다.

삭제가 확정되면 메시지는 content를 노출하지 않는 상태로 바뀐다.

초기 UX에서는 삭제된 메시지의 content와 placeholder를 모두 표시하지 않는다.

삭제가 실패하면 기존 메시지 상태를 유지하고 사용자에게 실패를 표시해야 한다.

---

### 5.4 메시지 중복 병합 기준

같은 메시지는 여러 경로로 도착할 수 있다.

예를 들어 사용자가 보낸 메시지는 클라이언트 임시 메시지, 전송 확정 결과, 실시간 이벤트, 재조회 결과로 반복해서 나타날 수 있다.

클라이언트는 이를 별도 메시지로 렌더링하지 않고 하나의 메시지로 병합해야 한다.

```text
NOT_IN_LIST
  -- 메시지 조회 결과 또는 새 메시지 이벤트 수신 -->
SENT

SENT
  -- 중복 메시지 수신 -->
SENT
```

서버 확정 메시지가 도착하면 클라이언트 임시 메시지는 서버 확정 메시지로 대체된다.

이미 화면에 있는 메시지와 같은 서버 확정 메시지가 다시 도착하면 새로 추가하지 않고 기존 메시지를 유지하거나 갱신한다.

서버 확정 후에는 서버가 부여한 메시지 순서와 최종 상태로 보정한다.

---

## 6. 읽음 상태와 표시

읽음 상태는 사용자가 어디까지 읽었는지와 다른 참여자가 내 메시지를 어느 정도 읽었는지를 표현한다.

클라이언트는 읽음 상태를 read cursor, read session, room unread count, message read receipt count로 나누어 해석한다.

### 6.1 read cursor

read cursor는 특정 사용자가 특정 room에서 어디까지 읽었는지를 나타내는 기준이다.

각 사용자는 참여 중인 room마다 자신의 read cursor를 가진다.

read cursor 이후의 메시지는 해당 사용자에게 unread 메시지로 해석된다.

다른 참여자의 read cursor가 내 메시지 이후를 가리키면, 그 참여자는 내 메시지를 읽은 것으로 해석된다.

read cursor는 room unread count와 message read receipt count의 기준이 된다.

read cursor의 source of truth는 서버다.

클라이언트는 읽음 표시를 먼저 갱신할 수 있지만, 서버가 확정한 read cursor와 다르면 서버 값을 기준으로 보정한다.

read cursor는 뒤로 이동하지 않는다.

---

### 6.2 read session 생명주기

read session은 사용자가 room 화면을 보고 있다고 판단되는 동안 유지되는 클라이언트 런타임 상태다.

read session 활성화는 서버에게 사용자가 현재 room 화면을 보고 있음을 알리는 런타임 신호다.

read session이 활성화되면 서버는 새 메시지를 전달한 뒤 read cursor 자동 갱신 여부를 판단할 수 있다.

markRead는 read session과 별개로, 클라이언트가 적절한 시점에 보내는 read cursor 보정 요청이다.

클라이언트는 새 메시지마다 markRead를 호출하지 않는다.

read cursor 보정 요청 후보 시점은 다음과 같다.

```text
- room 화면 진입
- 스크롤 하단 도달
- 화면 재활성화
- 재연결 후 누락 데이터 복구 완료
```

```text
NOT_VIEWING
  -- 사용자가 room 화면을 보기 시작 -->
VIEWING

VIEWING
  -- room 화면 이탈 또는 화면 비활성화 -->
NOT_VIEWING
```

room 화면 진입, room 화면 이탈, 탭 비활성화, 연결 끊김에 따라 바뀔 수 있다.

클라이언트는 room 진입, foreground 복귀, reconnect, scroll-to-bottom, room leave 같은 시점에 read cursor 보정이 필요한지 판단한다.

read cursor 보정이 실패하면 기존 읽음 표시를 즉시 폐기하지 않고 stale한 읽음 상태로 표현할 수 있다.

read cursor 보정이 확정되면 room unread count와 message read receipt count 표시를 보정한다.

---

### 6.3 room unread count 표시

room unread count는 현재 사용자가 room에서 아직 읽지 않은 메시지 수를 나타낸다.

클라이언트는 room unread count를 내가 참여한 방 목록의 badge 또는 room 진입 전 표시 정보로 사용할 수 있다.

room unread count는 현재 사용자 기준으로 서버가 계산한 요약 값이다.

클라이언트는 room unread count를 직접 계산하지 않는다.

클라이언트는 목록 진입, foreground 복귀, polling, 채팅방 복귀 시점에 서버 값으로 room unread count를 보정한다.

채팅방에 진입한 뒤 어떤 메시지부터 보여줄지는 room unread count가 아니라 메시지 조회 결과의 firstUnreadSequence를 기준으로 결정한다.

---

### 6.4 message read receipt count 표시

message read receipt count는 특정 room의 하나의 메시지를 현재 ACTIVE member 중 몇 명이 읽었는지 나타내는 수다.

클라이언트는 메시지 옆에 message read receipt count를 표시할 수 있다.

안 읽은 사람 수가 필요하면 클라이언트가 message read receipt count와 ACTIVE member snapshot을 기준으로 파생한다.

개념적으로 안 읽은 사람 수는 다음과 같이 계산할 수 있다.

```text
unreadReceiptCount =
  receiptTargetMemberCount - readReceiptCount
```

`receiptTargetMemberCount`는 작성자 본인을 제외한 읽음 표시 대상 참여자 수다.

작성자 본인은 message read receipt count와 파생 안 읽은 사람 수에 포함되지 않는 것으로 해석한다.

message read receipt count는 실시간 이벤트로 먼저 바뀔 수 있지만, 최종 값은 서버 조회 결과로 보정한다.

---

## 7. 실시간 연결

실시간 연결은 채팅방 화면을 빠르게 갱신하기 위한 수단이다.

클라이언트는 실시간 연결을 WebSocket 연결, room 구독, 재연결, 누락 복구로 나누어 해석한다.

### 7.1 연결 생명주기

연결 생명주기는 WebSocket 자체의 연결 상태를 나타낸다.

```text
DISCONNECTED
  -- 실시간 연결 시작 -->
CONNECTING

CONNECTING
  -- 실시간 연결 성공 -->
CONNECTED

CONNECTING
  -- 실시간 연결 실패 -->
DISCONNECTED

CONNECTED
  -- 연결 끊김 감지 -->
RECONNECTING

RECONNECTING
  -- 재연결 성공 -->
CONNECTED

RECONNECTING
  -- 재연결 실패 -->
DISCONNECTED
```

연결이 끊겨도 기존 화면 상태를 즉시 폐기하지 않는다.

연결이 끊긴 동안 화면 데이터는 stale할 수 있으며, 클라이언트는 이 상태를 사용자에게 표시해야 한다.

연결 생명주기는 WebSocket 연결 자체만 다룬다.

재연결 이후 누락 데이터 복구 흐름은 7.3에서 다룬다.

---

### 7.2 room 구독 생명주기

WebSocket이 연결되어 있어도 모든 room 이벤트를 받는 것은 아니다.

클라이언트는 사용자가 room 화면을 보고 있는 동안 해당 room 이벤트를 받을 수 있어야 한다.

```text
NOT_RECEIVING_ROOM_EVENTS
  -- room 실시간 수신 가능 -->
RECEIVING_ROOM_EVENTS

RECEIVING_ROOM_EVENTS
  -- 연결 끊김 감지 -->
ROOM_EVENTS_STALE

ROOM_EVENTS_STALE
  -- 재연결 후 room 이벤트 수신 복구 -->
RECEIVING_ROOM_EVENTS

RECEIVING_ROOM_EVENTS
  -- room 화면 이탈 또는 room 나가기 -->
NOT_RECEIVING_ROOM_EVENTS
```

room 구독은 실시간 이벤트 수신 범위를 결정한다.

room 화면을 이탈하거나 room에서 나가면 실시간 수신과 read session 신호를 함께 정리해야 한다.

---

### 7.3 재연결과 누락 복구

재연결은 WebSocket을 다시 연결하는 것만으로 끝나지 않는다.

클라이언트는 연결이 끊긴 동안 놓친 데이터를 복구해야 한다.

```text
CONNECTED
  -- 연결 끊김 감지 -->
RECONNECTING

RECONNECTING
  -- 재연결 성공 -->
CATCH_UP_PENDING

CATCH_UP_PENDING
  -- 누락 데이터 복구 성공 -->
CONNECTED

CATCH_UP_PENDING
  -- 누락 데이터 복구 실패 -->
CATCH_UP_FAILED
```

클라이언트는 마지막으로 처리한 메시지 이후의 데이터를 복구한다.

누락 복구 중에는 기존 메시지 목록을 유지하되, 화면이 최신이 아닐 수 있음을 표시해야 한다.

누락 복구 실패 시 사용자가 재시도할 수 있어야 한다.

---

### 7.4 연결 상태 표시

클라이언트는 사용자에게 다음 연결 상태를 표현할 수 있어야 한다.

```text
connecting:
  실시간 연결을 준비하는 상태

connected:
  실시간 이벤트를 받을 수 있는 상태

reconnecting:
  연결이 끊겨 복구 중인 상태

syncing:
  재연결 후 누락 데이터를 복구하는 상태

offline:
  실시간 갱신을 받을 수 없는 상태

sync failed:
  재연결은 되었지만 누락 데이터 복구에 실패한 상태
```

---

## 8. 실패 처리 원칙

클라이언트는 실패를 사용자 행동 실패와 동기화 실패로 나누어 처리한다.

사용자 행동 실패:

```text
- 메시지 전송 실패
- 메시지 수정 실패
- 메시지 삭제 실패
- room 참여 실패
- room 나가기 실패
```

동기화 실패:

```text
- 방 목록 조회 실패
- room 상세 조회 실패
- 실시간 연결 실패
- 재연결 실패
- 누락 데이터 복구 실패
- 읽음 상태 보정 실패
```

사용자 행동 실패는 사용자가 이해하고 복구할 수 있어야 한다.

```text
- 전송 실패 메시지는 재전송하거나 제거할 수 있어야 한다.
- 수정 실패는 기존 메시지 상태를 유지하고 실패를 표시한다.
- 삭제 실패는 기존 메시지 상태를 유지하고 실패를 표시한다.
- 참여 실패는 참여 전 상태로 돌아가고 실패를 표시한다.
- 나가기 실패는 참여 중 상태를 유지하고 실패를 표시한다.
```

동기화 실패는 화면 전체를 즉시 폐기하지 않는다.

```text
- 연결이 끊겨도 기존 메시지 목록을 즉시 비우지 않는다.
- 재연결 중에는 stale 상태를 표시한다.
- 누락 데이터 복구 실패 시 사용자가 재시도할 수 있어야 한다.
- 접근 불가가 확인되면 해당 room은 UNAVAILABLE 상태로 전환한다.
```

---

## 9. 설계 원칙 요약

다음 기준은 클라이언트 설계 전반에서 유지한다.

```text
1. 클라이언트는 서버 내부 상태를 그대로 노출하지 않고 사용자 인지 상태로 표현한다.
2. 서버 확정 상태와 클라이언트 임시 상태를 분리한다.
3. 실시간 이벤트는 빠른 반영 수단이며 최종 상태로 간주하지 않는다.
4. 같은 메시지는 여러 경로로 도착해도 하나의 메시지로 병합한다.
5. 서버 확정 후 메시지는 서버가 부여한 순서와 상태로 보정한다.
6. read cursor의 source of truth는 서버다.
7. 클라이언트는 새 메시지마다 markRead를 호출하지 않는다.
8. read session은 사용자가 room 화면을 보고 있음을 서버에 알리는 런타임 신호다.
9. 연결 끊김은 화면 폐기가 아니라 stale 표시와 복구 흐름으로 다룬다.
10. 실패는 숨기지 않고 사용자가 재시도하거나 복구할 수 있게 표현한다.
```
