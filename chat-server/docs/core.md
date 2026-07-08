# 공용 오픈 채팅방 코어 설계

## 1. 목적

본 문서는 공용 오픈 채팅방 모듈이 해결해야 하는 핵심 문제와 그에 따른 도메인 설계 방향을 정의한다.

코어 설계의 목적은 다음과 같다.

```text
- 방의 생성, 참여, 메시지, 읽음, 종료 흐름을 일관되게 처리한다.
- 현재 참여 상태와 과거 참여 구간을 분리한다.
- 메시지 조회 가능 범위를 명확히 제한한다.
- 메시지 순서와 실시간 복구 기준을 시간보다 sequence로 판단한다.
- 읽음 상태와 실시간 읽음 표시를 분리한다.
- WebSocket 연결 상태와 DB 영속 상태를 분리한다.
```

---

## 2. 범위

현재 코어 범위는 다음 기능으로 제한한다.

```text
- 오픈 채팅방 생성
- 오픈 채팅방 목록 조회
- 오픈 채팅방 상세 조회
- 오픈 채팅방 참여
- 오픈 채팅방 메시지 송수신
- 메시지 수정
- 메시지 삭제
- 메시지 읽음 처리
- room별 unread count 조회
- 메시지별 read receipt count 조회
- 오픈 채팅방 나가기
- 오픈 채팅방 종료
- WebSocket 연결 및 room 구독
```

파일/이미지 메시지, 알림, 신고/제재, 반응, 장기 감사 로그는 현재 코어 범위에 포함하지 않는다.

---

## 3. 인증과 room 권한 판단

모든 요청은 JWT access token 검증을 선행한다.

JWT는 사용자가 누구인지 식별하는 데 사용한다.

```text
authenticatedUser.userId = jwt.sub
```

room owner 여부, 참여자 여부, 메시지 작성자 여부는 JWT role만으로 판단하지 않는다.

room에 대한 권한은 room 도메인 데이터와 authenticated user의 관계로 판단한다.

```text
room owner:
  room.ownerUserId == authenticatedUser.userId

room participant:
  해당 room에서 사용자의 membership이 active

message sender:
  message.senderUserId == authenticatedUser.userId
```

---

## 4. 채팅방

채팅방은 사용자 기능 가능 여부를 나타내는 생명주기와 운영 개입 필요 여부를 분리해서 관리한다.

### 4.1 방 생명주기

오픈 채팅방은 사용자 기능 가능 여부를 기준으로 열림과 종료 상태를 가진다.

```text
NOT_CREATED
  -- createRoom -->
ACTIVE

ACTIVE
  -- closeRoom -->
CLOSED
```

`ACTIVE` room은 일반 사용자가 조회, 참여, 메시지 조회, 메시지 전송을 할 수 있는 상태다.

`CLOSED` room은 일반 사용자 API에서 숨긴다.

```text
CLOSED room:
  - 목록 조회 제외
  - 상세 조회 불가
  - 메시지 조회 불가
  - 참여 불가
  - 메시지 전송 불가
```

일반 사용자에게 종료된 room의 존재를 숨기기 위해 `CLOSED` room 접근은 기본적으로 `404 Not Found`로 처리한다.

---

### 4.2 방 운영 상태

방의 열림/종료 상태와 방 운영 상태는 분리한다.

방이 `ACTIVE`여도 owner 비활성화 등으로 운영자 조치가 필요한 상태가 될 수 있다.

```text
NORMAL
  -- owner unavailable -->
SYSTEM_MANAGED
```

`SYSTEM_MANAGED`는 owner 비활성화 등으로 운영자 조치가 필요한 room 운영 상태다.

운영자는 `SYSTEM_MANAGED` room에 대해 다음 조치를 수행할 수 있다.

```text
- 다른 active member에게 owner transfer
- closeRoom
```

owner 없는 `ACTIVE` room은 허용하지 않는다.

---

## 5. 참여자

참여자는 현재 room에 참여 중인지와 어떤 메시지 범위를 볼 수 있는지를 분리해서 관리한다.

### 5.1 참여 상태

현재 참여 상태는 “지금 이 사용자가 room에 참여 중인가”를 판단한다.

```text
현재 참여 상태:
  NONE
  ACTIVE
  LEFT
```

참여 상태 전이는 다음과 같다.

```text
NONE
  -- joinRoom -->
ACTIVE

ACTIVE
  -- leaveRoom -->
LEFT

LEFT
  -- joinRoom -->
ACTIVE
```

owner는 일반 leaveRoom으로 room을 나가지 않는다.

owner가 방 이용을 종료하려면 closeRoom을 호출한다.

### 5.2 참여 구간

참여 구간은 “이 사용자가 어떤 메시지 범위를 볼 수 있는가”를 판단한다.

```text
참여 구간:
  joinedSequence
  leftSequence
```

재입장 시 이전 참여 구간을 이어 쓰지 않고 새로운 참여 구간을 만든다.

사용자는 현재 참여 구간이 시작된 이후 생성된 메시지만 조회할 수 있다.

```text
입장 전 메시지:
  조회 불가

퇴장 중 생성된 메시지:
  재입장 후에도 현재 참여 구간 조회 범위에 포함되지 않음
```

### 5.3 메시지 조회 범위

메시지 조회 가능 범위는 현재 참여 상태만으로 판단하지 않는다.

현재 열린 참여 구간이 있는 사용자는 해당 참여 구간이 시작된 이후 생성된 메시지만 조회할 수 있다.

---

## 6. 메시지

메시지는 room 안에서 순서, 생성 중복 방지 기준, 수정 방식, 삭제 표시 방식을 가진다.

### 6.1 메시지 순서

메시지 순서와 복구 기준은 시간보다 room 내부 sequence를 사용한다.

```text
messageId:
  전체 시스템에서 메시지를 식별

roomSequence:
  특정 room 안에서 메시지 순서, 페이지네이션, 누락 복구, read cursor 계산에 사용하는 순서 값
```

메시지 정렬, 페이지네이션, WebSocket 누락 복구는 `roomSequence` 기준으로 처리한다.

`roomSequence`는 room마다 독립적으로 증가해야 하며, 전역 `messageId`와 같은 값으로 간주하지 않는다.

서버는 메시지 저장이 확정되는 시점에 room 안에서 다음 `roomSequence`를 부여한다.

저장에 성공한 메시지 기준으로 `roomSequence`가 중복되거나 역전되면 안 된다.

### 6.2 메시지 생성

메시지 전송은 현재 참여 중인 사용자만 수행할 수 있다.

메시지 전송 성공 시 메시지는 room 안에서 고유한 `roomSequence`를 가진다.

같은 메시지 전송 요청이 중복 도착해도 같은 메시지가 여러 번 생성되면 안 된다.

이를 위해 클라이언트는 메시지 전송 요청마다 `clientMessageId`를 생성한다.

```text
roomId + senderUserId + clientMessageId
  -> 하나의 message
```

### 6.3 메시지 수정

메시지 수정은 상태 전이가 아니라 속성 변경이다.

```text
수정되는 값:
  - content
  - editedAt
  - editVersion
```

### 6.4 메시지 삭제

메시지 삭제는 사용자에게 표시되는 방식이 바뀌므로 visibility 변화로 관리한다.

```text
VISIBLE
  -- deleteMessage -->
DELETED_BY_USER
```

삭제된 메시지는 물리 삭제하지 않는다.

일반 사용자 응답에서는 삭제된 메시지의 content를 노출하지 않는다.

---

## 7. 읽음 상태와 표시

읽음 상태는 사용자별 read cursor로 관리한다.

read cursor는 사용자가 특정 room에서 어디까지 읽었는지 나타낸다.

```text
lastReadSequence = N
  -> 사용자가 해당 room에서 N번 메시지까지 읽음
```

read cursor는 감소하지 않는다.

read cursor는 현재 참여 구간 범위 안에서만 갱신할 수 있다.

room별 unread count는 사용자의 read cursor 이후 메시지 수로 계산한다.

메시지별 read receipt count는 해당 메시지 sequence 이상을 읽은 사용자 수로 계산한다.

작성자 본인은 read receipt count에서 제외한다.

실시간 읽음 표시는 런타임 read session으로 보정할 수 있다.

사용자가 현재 room 화면을 보고 있는 경우, 서버는 새 메시지 broadcast 시 해당 사용자의 read cursor를 자동 갱신할 수 있다.

읽음 관련 WebSocket 이벤트는 영속 전달 보장 대상이 아니다.

정확한 unread count와 read receipt count는 메시지 조회 시 read cursor 기준으로 다시 계산할 수 있어야 한다.

---

## 8. 실시간 연결

실시간 연결은 DB에 저장되는 도메인 상태가 아니라 현재 서버가 관리하는 런타임 상태다.

### 8.1 WebSocket 연결

WebSocket 연결과 room 구독은 DB 영속 상태가 아니라 서버 런타임 상태다.

```text
DISCONNECTED
  -- connect(valid JWT) -->
CONNECTED

CONNECTED
  -- subscribeRoom(room active && membership active) -->
SUBSCRIBED

SUBSCRIBED
  -- unsubscribe or disconnect -->
CONNECTED or DISCONNECTED
```

WebSocket 연결은 userId 단독이 아니라 connectionId 기준으로 관리한다.

한 사용자가 여러 connection을 가질 수 있기 때문이다.

```text
connectionId -> userId
connectionId -> subscribedRoomIds
roomId -> connectionIds
userId -> connectionIds
```

### 8.2 room 구독

구독 요청 시점마다 room 상태, 참여 상태, 참여 구간 유효성을 다시 검증한다.

WebSocket 연결 종료는 참여 상태를 변경하지 않는다.

### 8.3 재연결과 누락 복구

클라이언트는 재연결 후 마지막으로 처리한 `roomSequence` 이후 메시지를 조회하여 누락 메시지를 복구할 수 있다.

---

## 9. 상태별 허용 동작

### 9.1 room 상태 기준

| room 상태 | 목록 조회 | 상세 조회 | 참여 | 메시지 조회 | 메시지 전송 | 종료 |
|---|---:|---:|---:|---:|---:|---:|
| `ACTIVE` | 가능 | 가능 | 가능 | 가능 | 참여자 가능 | owner 가능 |
| `CLOSED` | 불가 | 불가 | 불가 | 불가 | 불가 | 불가 |

---

### 9.2 참여 상태 기준

| 참여 상태 | 메시지 조회 | 메시지 전송 | 나가기 | 참여 |
|---|---:|---:|---:|---:|
| `NONE` | 불가 | 불가 | 불가 | 가능 |
| `ACTIVE` | 가능 | 가능 | 가능 | 성공 처리 |
| `LEFT` | 불가 | 불가 | 성공 처리 가능 | 가능 |

메시지 조회 가능 범위는 현재 참여 상태만으로 판단하지 않고 현재 참여 구간 기준으로 판단한다.

---

### 9.3 메시지 visibility 기준

| 메시지 visibility | 일반 조회 | 수정 | 삭제 |
|---|---:|---:|---:|
| `VISIBLE` | 가능 | 작성자 가능 | 작성자 가능 |
| `DELETED_BY_USER` | placeholder 또는 제외 | 불가 | 성공 처리 가능 |

---

## 10. 실패 처리 원칙

실패는 확정된 도메인 상태를 변경하지 않는다.

인증 실패, 권한 부족, 참여 상태 불일치, 종료된 room, 조회 가능 범위 초과, 중복 요청은 서로 다른 실패로 구분한다.

구체적인 command별 실패 응답과 idempotency 응답은 command 명세에서 정의한다.

---

## 11. 설계 원칙 요약

1. DB에 확정된 상태를 source of truth로 둔다.
2. room 생명주기, 방 운영 상태, 참여 상태, 참여 구간, 메시지 순서, 읽음 상태를 분리한다.
3. room 권한은 인증 token이 아니라 room 도메인 데이터와 userId의 관계로 판단한다.
4. 사용자는 현재 참여 구간 이후의 메시지만 조회할 수 있다.
5. 실시간 전달은 조회 가능한 상태를 빠르게 전파하는 수단이며, 상태의 원천은 아니다.
