# 오픈 채팅방 클라이언트 React State Model

## 1. 목적

이 문서는 `core.md`, `domain-view.md`, `queries.md`, `commands.md`, `screen-model.md`에서 정의한 상태와 행동을 React 애플리케이션에서 어떤 상태 소유자와 Hook으로 연결할지 정의한다.

이 문서는 실제 React 구현에 앞서 상태 소유권, Hook interface, 화면별 상태 연결과 렌더링 의존성을 JavaScript 기반 React 구현 스케치로 구체화한다.

```text
- 외부 상태와 command 상태의 저장 위치
- pending domain 상태와 realtime runtime 상태의 저장 위치
- 각 화면 영역의 렌더링이 의존하는 상태
- 프론트엔드 도메인 모델과 capability의 파생 위치
- 상태 변경 시 다시 렌더링되는 화면 범위
```

이 문서의 JavaScript와 JSX는 상태 배치, Hook 책임, 파생 로직, 렌더링 의존성을 표현하는 React 구현 스케치이며 실행 가능한 완성 코드가 아니다.

---

## 2. 프론트엔드 상태의 React 배치

프론트엔드 도메인 모델 전체를 하나의 React state나 reducer에 저장하지 않는다.

각 상태는 source와 생명주기에 따라 다음 위치에 배치한다.

```text
서버 query로 확인한 외부 상태
  -> Query Cache

command 요청의 실행 상태와 결과
  -> Mutation Cache

클라이언트가 받아들였지만 서버 상태와 아직 정합되지 않은 사용자 행동
  -> Client Domain Store

WebSocket 연결, room 구독, read session 상태
  -> Realtime Store

현재 사용자와 route
  -> Auth / Router

현재 화면에서만 유효한 입력과 선택 상태
  -> Component State
```

Mutation Cache와 Client Domain Store는 모두 `pending`, `error` 상태를 가질 수 있지만 관리하는 대상과 생명주기가 다르다. Mutation Cache는 개별 서버 요청의 실행 과정을 관리하고, Client Domain Store는 Query Cache나 realtime event와 정합될 때까지 유지해야 하는 사용자 행동을 관리한다.

기존 상태에서 계산할 수 있는 프론트엔드 도메인 모델과 capability는 별도 store에 저장하지 않는다.

```js
const accessScope = deriveMessageAccessScope({
  room,
  participation,
});

const capability = deriveRoomCapability({
  room,
  participation,
  pendingParticipation,
  connection,
  pageContext,
});
```

### 2.1 공통 파생 함수 interface

페이지가 달라도 같은 프론트엔드 도메인 모델과 capability는 하나의 파생 함수 계약을 사용한다.

```js
// ClientRoomDetail.currentUserParticipationStatus를 해석한다.
function deriveParticipation({ room }) {}

function deriveRoomCapability({
  room,
  participation,
  pendingParticipation,
  connection,
  pageContext,
}) {}

function deriveParticipationCapability({
  room,
  participation,
  pendingParticipation,
  roomCapability,
  pageContext,
}) {}
```

`participation`, `pendingParticipation`, `connection`은 해당 페이지가 입력을 가지고 있지 않으면 `null`일 수 있다.

각 상태를 읽고 변경하는 Hook interface와 렌더링 의존성은 페이지별 React 구현 스케치에서 정의한다.

---

## 3. 내가 참여한 방 목록 페이지

### 3.1 Hook interface와 파생 함수

```js
// Owner: Query Cache
// Internal: listRooms query
// Reconciled by: getUnreadCounts, listRooms refresh
// Returns: { roomIds, status, isRefreshing, error, refresh }
function useJoinedRoomsQuery() {}

// Owner: Query Cache
// Internal: joined rooms query에서 roomId에 해당하는 summary 선택
// Returns: ClientRoomSummary | null
function useJoinedRoomSummary(roomId) {}

// Pure derivation
// Returns: joined rooms content state
function deriveJoinedRoomsContentState({
  roomCount,
  queryStatus,
}) {}

```

`useJoinedRoomsQuery()`와 `useJoinedRoomSummary(roomId)`는 같은 joined rooms Query Cache를 읽으며, 후자는 별도 서버 query 없이 전달받은 `roomId`의 `ClientRoomSummary`만 선택한다.

### 3.2 React 구현 스케치

```jsx
function JoinedRoomsPage() {
  const roomsQuery = useJoinedRoomsQuery();

  const contentState =
    deriveJoinedRoomsContentState({
      roomCount: roomsQuery.roomIds.length,
      queryStatus: roomsQuery.status,
    });

  return (
    <JoinedRoomsScreen
      contentState={contentState}
      isRefreshing={roomsQuery.isRefreshing}
      queryError={roomsQuery.error}
      onRefresh={roomsQuery.refresh}
    >
      <JoinedRoomList
        roomIds={roomsQuery.roomIds}
      />
    </JoinedRoomsScreen>
  );
}

function JoinedRoomList({ roomIds }) {
  return roomIds.map((roomId) => (
    <JoinedRoomItem
      key={roomId}
      roomId={roomId}
    />
  ));
}

function JoinedRoomItem({ roomId }) {
  const room = useJoinedRoomSummary(roomId);

  if (!room) {
    return null;
  }

  const capability = deriveRoomCapability({
    room,
    participation: null,
    pendingParticipation: null,
    connection: null,
    pageContext: "JOINED_ROOM_LIST",
  });

  return (
    <RoomListItem
      room={room}
      disabled={!capability.canEnterChat}
      onSelect={() => {
        if (capability.canEnterChat) {
          navigateToChat(roomId);
        }
      }}
    />
  );
}
```

### 3.3 렌더링 의존성

```text
JoinedRoomsPage
  <- joined rooms query status
  <- roomId 목록
  <- refreshing 상태
  <- query error

JoinedRoomList
  <- roomId 목록

JoinedRoomItem(roomId)
  <- 해당 roomId의 ClientRoomSummary
```

---

## 4. 참여 가능한 방 조회 페이지

### 4.1 Hook interface와 파생 함수

```js
// Owner: Query Cache
// Internal: listRooms query
// Returns: { roomIds, status, isRefreshing, error, refresh }
function useDiscoverableRoomsQuery() {}

// Owner: Query Cache
// Internal: discoverable rooms query에서 room summary 선택
// Returns: ClientRoomSummary | null
function useDiscoverableRoomSummary(roomId) {}

// Owner: Query Cache
// Internal: getRoomDetail query
// Returns: { room, status, error, refresh }
function useRoomDetailQuery(roomId) {}

// Owner: Client Domain Store
// Key: roomId + action
// Returns: 해당 action의 PendingParticipationAction | null
function usePendingParticipation(roomId, action) {}

// Owner: Mutation Cache
// Internal: joinRoom command
// Returns: { status, error, execute, retry }
function useJoinRoomCommand(roomId) {}

// Pure derivation
// Returns: discoverable rooms content state
function deriveDiscoverableRoomsContentState({
  roomCount,
  queryStatus,
}) {}

// Pure derivation
// Returns: room detail content state
function deriveRoomDetailContentState({
  room,
  queryStatus,
}) {}
```

`useDiscoverableRoomsQuery()`와 `useDiscoverableRoomSummary(roomId)`는 같은 discoverable rooms Query Cache를 읽으며, 후자는 별도 서버 query 없이 전달받은 `roomId`의 `ClientRoomSummary`만 선택한다.

### 4.2 React 구현 스케치

```jsx
function DiscoverableRoomsPage() {
  const roomsQuery =
    useDiscoverableRoomsQuery();

  const contentState =
    deriveDiscoverableRoomsContentState({
      roomCount: roomsQuery.roomIds.length,
      queryStatus: roomsQuery.status,
    });

  const [selectedRoomId, setSelectedRoomId] =
    useState(null);

  return (
    <DiscoverableRoomsScreen
      contentState={contentState}
      isRefreshing={roomsQuery.isRefreshing}
      queryError={roomsQuery.error}
      onRefresh={roomsQuery.refresh}
    >
      <DiscoverableRoomList
        roomIds={roomsQuery.roomIds}
        selectedRoomId={selectedRoomId}
        onSelectRoom={setSelectedRoomId}
      />

      {selectedRoomId && (
        <DiscoverableRoomDetail
          roomId={selectedRoomId}
        />
      )}
    </DiscoverableRoomsScreen>
  );
}

function DiscoverableRoomList({
  roomIds,
  selectedRoomId,
  onSelectRoom,
}) {
  return roomIds.map((roomId) => (
    <DiscoverableRoomItem
      key={roomId}
      roomId={roomId}
      selected={roomId === selectedRoomId}
      onSelect={onSelectRoom}
    />
  ));
}

function DiscoverableRoomItem({
  roomId,
  selected,
  onSelect,
}) {
  const room = useDiscoverableRoomSummary(roomId);

  if (!room) {
    return null;
  }

  return (
    <RoomListItem
      room={room}
      selected={selected}
      onSelect={() => onSelect(roomId)}
    />
  );
}

function DiscoverableRoomDetail({ roomId }) {
  const roomQuery = useRoomDetailQuery(roomId);
  const pendingParticipation =
    usePendingParticipation(roomId, "JOIN");
  const joinCommand = useJoinRoomCommand(roomId);

  const participation = deriveParticipation({
    room: roomQuery.room,
  });

  const roomCapability = deriveRoomCapability({
    room: roomQuery.room,
    participation,
    pendingParticipation,
    connection: null,
    pageContext: "DISCOVERABLE_ROOM_DETAIL",
  });

  const contentState =
    deriveRoomDetailContentState({
      room: roomQuery.room,
      queryStatus: roomQuery.status,
    });

  return (
    <RoomDetail
      contentState={contentState}
      queryError={roomQuery.error}
      room={roomQuery.room}
      participationStatus={
        participation?.status ?? null
      }
      joinStatus={
        pendingParticipation?.status ?? null
      }
      joinCommandStatus={joinCommand.status}
      joinError={joinCommand.error}
      canJoin={roomCapability.canJoin}
      canEnterChat={roomCapability.canEnterChat}
      onJoin={() => {
        if (roomCapability.canJoin) {
          joinCommand.execute();
        }
      }}
      onRetryJoin={joinCommand.retry}
      onEnterChat={() => {
        if (roomCapability.canEnterChat) {
          navigateToChat(roomId);
        }
      }}
    />
  );
}
```

### 4.3 joinRoom 실행과 상태 반영

`useJoinRoomCommand`는 `useMutation`으로 `joinRoom`의 실행 상태를 관리한다. Lifecycle callback에서는 참여 요청을 Client Domain Store에 기록하고, 서버 응답에 따라 Query Cache와 pending participation을 정합시킨다.

```js
function useJoinRoomCommand(roomId) {
  const mutation = useMutation({
    mutationFn: () => joinRoom(roomId),

    onMutate: () => {
      // 사용자의 참여 요청을 클라이언트 도메인 상태에 기록한다.
      putPendingParticipation({
        roomId,
        action: "JOIN",
        status: "PENDING",
        requestedAt: now(),
      });
    },

    onSuccess: (response) => {
      // 서버 응답으로 외부 상태를 보정한다.
      reconcileRoomDetail(roomId, response);

      // 참여 요청이 서버 상태와 정합되었으므로 제거한다.
      removePendingParticipation(roomId, "JOIN");
    },

    onError: (error) => {
      // 사용자가 요청했던 참여 행동의 실패 상태를 유지한다.
      failPendingParticipation(
        roomId,
        "JOIN",
        error,
      );
    },
  });

  return {
    status: mutation.status,
    error: mutation.error,
    execute: mutation.mutate,
    retry: mutation.mutate,
  };
}
```

### 4.4 렌더링 의존성

```text
DiscoverableRoomsPage
  <- discoverable rooms query state
  <- roomId 목록
  <- selectedRoomId

DiscoverableRoomList
  <- roomId 목록
  <- selectedRoomId

DiscoverableRoomItem(roomId)
  <- 해당 roomId의 ClientRoomSummary
  <- selected 여부

DiscoverableRoomDetail(roomId)
  <- 해당 roomId의 ClientRoomDetail query state
  <- PendingParticipationAction
  <- joinRoom command 상태
```

---

## 5. 채팅 페이지

### 5.1 Hook interface와 파생 함수

```js
/* --- Auth / Router --- */

// Returns: 현재 사용자 context
function useCurrentUser() {}

/* --- Query Cache --- */

// Internal: getRoomDetail
// Returns: query state와 ClientRoomDetail
function useRoomDetailQuery(roomId) {}

// Internal: listMessages, catchUpMessages
// Reconciled by: command response와 message server event
// Returns: query state, confirmed ClientMessage 목록,
//          firstUnreadSequence, 이전 메시지 조회 interface
function useConfirmedMessages(roomId) {}

// Internal: getReadCursorSnapshot query와 message selector
// Reconciled by: READ_CURSOR_UPDATED
// Returns: 해당 message의 read receipt 표시 상태
function useMessageReadReceipt(roomId, message) {}

/* --- Mutation Cache --- */

// Internal: sendMessage
function useSendMessageCommand(roomId) {}

// Internal: failed PendingMessage의 sendMessage 재실행
// Contract: 기존 clientMessageId를 유지한다.
function useRetryMessageCommand(
  roomId,
  clientMessageId,
) {}

// Internal: editMessage
// Key: roomId + messageId
// Contract: messageId가 없으면 disabled command를 반환하며,
//           execute를 호출해도 서버 요청을 보내지 않는다.
function useEditMessageCommand(roomId, messageId) {}

// Internal: deleteMessage
// Key: roomId + messageId
// Contract: messageId가 없으면 disabled command를 반환하며,
//           execute를 호출해도 서버 요청을 보내지 않는다.
function useDeleteMessageCommand(roomId, messageId) {}

// Internal: leaveRoom
function useLeaveRoomCommand(roomId) {}

/* --- Client Domain Store --- */

// Key: roomId + clientMessageId
// Returns: 해당 room의 PendingMessage 목록
function usePendingMessages(roomId) {}

// Key: roomId + clientMessageId
// Returns: PendingMessage | null
function usePendingMessage(roomId, clientMessageId) {}

// Key: roomId + action
// Returns: 해당 action의 PendingParticipationAction | null
function usePendingParticipation(roomId, action) {}

/* --- Realtime Store --- */

// Returns: ClientRealtimeConnection
function useRealtimeConnection() {}

// Key: roomId
// Returns: ClientRoomSubscription
function useRoomSubscription(roomId) {}

/* --- Pure derivation: frontend domain model and capability --- */

// Returns: ClientMessageAccessScope
function deriveMessageAccessScope({
  room,
  participation,
}) {}

// Returns: 표시 순서로 병합한
//          (ClientMessage | PendingMessage)[]
// Contract: messageId가 있으면 ClientMessage,
//           없으면 PendingMessage로 구분한다.
// Order: confirmed는 orderKey, pending은 createdAt 순으로 정렬하고
//        pending을 confirmed 뒤에 배치한다.
function deriveDisplayMessages({
  confirmed,
  pending,
}) {}

// Returns: ClientMessageCapability
function deriveMessageCapability({
  message,
  room,
  currentUser,
  editCommandStatus,
  deleteCommandStatus,
}) {}

/* --- Pure derivation: screen state --- */

// Returns: { status, reason }
function deriveChatAvailability({
  roomQuery,
  participation,
  accessScope,
}) {}

// Returns: message timeline content state
function deriveMessageTimelineContentState({
  messageCount,
  queryStatus,
}) {}
```

파생 함수는 프론트엔드 도메인 모델, capability, 여러 상태 입력의 화면 의미를 계산한다. room 이름, message 내용처럼 별도 판단이 필요 없는 표시 정보는 새로운 model 객체로 다시 포장하지 않고 표시 컴포넌트의 명시적인 props로 전달한다.

### 5.2 React 구현 스케치

#### 5.2.1 페이지 구성과 lifecycle

`ChatPage`는 화면 영역을 구성하고, 채팅 진입과 이탈에 필요한 lifecycle 처리는 `useChatRoomLifecycle`에 위임한다.

```jsx
function ChatPage({ roomId }) {
  useChatRoomLifecycle(roomId);

  return (
    <ChatScreen>
      <ChatHeader>
        <RoomHeader roomId={roomId} />
        <ConnectionNotice roomId={roomId} />
      </ChatHeader>

      <ChatAvailabilityBoundary roomId={roomId}>
        <MessageTimeline roomId={roomId} />
      </ChatAvailabilityBoundary>
      <MessageComposer roomId={roomId} />
    </ChatScreen>
  );
}

// 현재 route의 chat room을 realtime 복구 대상으로 등록하고,
// cleanup에서 read와 subscription 상태를 정리한다.
function useChatRoomLifecycle(roomId) {
  useEffect(() => {
    setActiveChatRoomId(roomId);
    ensureRealtimeConnected();

    return () => {
      markRead(roomId);
      deactivateReadSession(roomId);
      unsubscribeRoom(roomId);
      clearActiveChatRoomId(roomId);
    };
  }, [roomId]);
}
```

#### 5.2.2 Room 상태와 접근 제어

`RoomHeader`는 나가기 가능 여부와 진행 상태를 `ClientParticipationCapability`와 `PendingParticipationAction(LEAVE)`에서 읽는다.

```jsx
function RoomHeader({ roomId }) {
  const roomQuery = useRoomDetailQuery(roomId);
  const pendingParticipation =
    usePendingParticipation(roomId, "LEAVE");
  const leaveCommand = useLeaveRoomCommand(roomId);

  const participation = deriveParticipation({
    room: roomQuery.room,
  });

  const roomCapability = deriveRoomCapability({
    room: roomQuery.room,
    participation,
    pendingParticipation,
    connection: null,
    pageContext: "CHAT",
  });

  const participationCapability =
    deriveParticipationCapability({
      room: roomQuery.room,
      participation,
      pendingParticipation,
      roomCapability,
      pageContext: "CHAT",
    });

  return (
    <RoomHeaderView
      roomName={roomQuery.room?.name ?? ""}
      queryStatus={roomQuery.status}
      queryError={roomQuery.error}
      canLeave={
        participationCapability.canLeaveRoom
      }
      leaveStatus={
        pendingParticipation?.status ?? null
      }
      onLeave={() => {
        if (participationCapability.canLeaveRoom) {
          leaveCommand.execute();
        }
      }}
    />
  );
}
```

`ConnectionNotice`는 Realtime Store의 connection과 room subscription을 읽어 현재 동기화 상태를 표시한다.

```jsx
function ConnectionNotice({ roomId }) {
  const connection = useRealtimeConnection();
  const subscription = useRoomSubscription(roomId);

  return (
    <ConnectionNoticeView
      connectionStatus={connection.status}
      subscriptionStatus={subscription.status}
      catchUpRequired={
        subscription.catchUpRequired
      }
    />
  );
}
```

`ChatAvailabilityBoundary`는 확인된 room과 participation 상태를 기준으로 채팅 영역을 표시할 수 있는지 판단한다.

```jsx
function ChatAvailabilityBoundary({
  roomId,
  children,
}) {
  const roomQuery = useRoomDetailQuery(roomId);

  const participation = deriveParticipation({
    room: roomQuery.room,
  });

  const accessScope = deriveMessageAccessScope({
    room: roomQuery.room,
    participation,
  });

  const availability = deriveChatAvailability({
    roomQuery,
    participation,
    accessScope,
  });

  if (availability.status === "LOADING") {
    return <ChatLoading />;
  }

  if (availability.status === "UNAVAILABLE") {
    return (
      <ChatUnavailable
        reason={availability.reason}
      />
    );
  }

  return children;
}
```

#### 5.2.3 Message 표시와 입력

`MessageTimeline`은 confirmed message와 pending message를 하나의 표시 순서로 조합하고, 메시지 목록 영역의 표시 상태를 구성한다.

```jsx
function MessageTimeline({ roomId }) {
  const confirmed = useConfirmedMessages(roomId);
  const pending = usePendingMessages(roomId);

  const messages = deriveDisplayMessages({
    confirmed: confirmed.messages,
    pending,
  });

  const contentState =
    deriveMessageTimelineContentState({
      messageCount: messages.length,
      queryStatus: confirmed.status,
    });

  return (
    <MessageTimelineView
      contentState={contentState}
      isRefreshing={confirmed.isRefreshing}
      error={confirmed.error}
      firstUnreadSequence={
        confirmed.firstUnreadSequence
      }
      canLoadPrevious={confirmed.canLoadPrevious}
      onLoadPrevious={confirmed.loadPrevious}
      onRetry={confirmed.retry}
    >
      {messages.map((message) => {
        if (message.messageId == null) {
          return (
            <PendingMessageItem
              key={message.clientMessageId}
              roomId={roomId}
              message={message}
            />
          );
        }

        return (
          <ConfirmedMessageItem
            key={message.clientMessageId}
            roomId={roomId}
            message={message}
          />
        );
      })}
    </MessageTimelineView>
  );
}
```

`ConfirmedMessageItem`은 서버에서 확정된 message의 action을 구성한다. 읽음 표시는 `MessageReadReceipt`가 별도 서버 query 없이 같은 read cursor snapshot Query Cache에서 선택해 표시한다.

```jsx
function ConfirmedMessageItem({ roomId, message }) {
  const currentUser = useCurrentUser();
  const roomQuery = useRoomDetailQuery(roomId);
  const editCommand = useEditMessageCommand(
    roomId,
    message.messageId,
  );
  const deleteCommand = useDeleteMessageCommand(
    roomId,
    message.messageId,
  );

  const capability = deriveMessageCapability({
    message,
    room: roomQuery.room,
    currentUser,
    editCommandStatus: editCommand.status,
    deleteCommandStatus: deleteCommand.status,
  });

  return (
    <MessageBubble
      content={message.content}
      sender={message.sender}
      sentAt={message.sentAt}
      displayState={message.displayState}
      canEdit={capability.canEdit}
      canDelete={capability.canDelete}
      editStatus={editCommand.status}
      deleteStatus={deleteCommand.status}
      onEdit={editCommand.execute}
      onDelete={deleteCommand.execute}
    >
      <MessageReadReceipt
        roomId={roomId}
        message={message}
      />
    </MessageBubble>
  );
}
```

`PendingMessageItem`은 `PendingMessage.status`를 그대로 표시하고, 실패한 메시지에 기존 `clientMessageId`를 유지하는 retry command를 연결한다.

```jsx
function PendingMessageItem({ roomId, message }) {
  const retryCommand = useRetryMessageCommand(
    roomId,
    message.clientMessageId,
  );

  return (
    <PendingMessageBubble
      content={message.content}
      sender={message.sender}
      createdAt={message.createdAt}
      status={message.status}
      canRetry={retryCommand.canExecute}
      onRetry={retryCommand.execute}
    />
  );
}
```

confirmed message의 읽음 표시만 read cursor snapshot을 읽는다.

```jsx
function MessageReadReceipt({ roomId, message }) {
  const receipt = useMessageReadReceipt(
    roomId,
    message,
  );

  return (
    <MessageReadReceiptView
      state={receipt.state}
      readReceiptCount={receipt.readReceiptCount}
      derivedUnreadReceiptCount={
        receipt.derivedUnreadReceiptCount
      }
    />
  );
}
```

`MessageComposer`는 draft를 소유하고, room과 participation capability로 입력과 전송 가능 여부를 구성한다.

```jsx
function MessageComposer({ roomId }) {
  const [draft, setDraft] = useState("");

  const roomQuery = useRoomDetailQuery(roomId);
  // 서버 participation이 아직 JOINED여도
  // LEAVE 요청 중에는 새 메시지를 보내지 않는다.
  const pendingParticipation =
    usePendingParticipation(roomId, "LEAVE");
  const sendCommand = useSendMessageCommand(roomId);

  const participation = deriveParticipation({
    room: roomQuery.room,
  });

  const roomCapability = deriveRoomCapability({
    room: roomQuery.room,
    participation,
    pendingParticipation,
    connection: null,
    pageContext: "CHAT",
  });

  const participationCapability =
    deriveParticipationCapability({
      room: roomQuery.room,
      participation,
      pendingParticipation,
      roomCapability,
      pageContext: "CHAT",
    });

  const canSubmit =
    draft.trim().length > 0 &&
    roomCapability.canSendMessage &&
    participationCapability.canWriteMessages;

  function send() {
    if (!canSubmit) {
      return;
    }

    sendCommand.execute({ content: draft });
    setDraft("");
  }

  return (
    <MessageComposerView
      value={draft}
      canSendMessage={
        roomCapability.canSendMessage
      }
      canWriteMessages={
        participationCapability.canWriteMessages
      }
      canSubmit={canSubmit}
      onChange={setDraft}
      onSend={send}
    />
  );
}
```

### 5.3 leaveRoom 실행과 상태 반영

`useLeaveRoomCommand`는 `useMutation`으로 `leaveRoom`의 실행 상태를 관리한다. Lifecycle callback에서는 사용자의 나가기 요청을 Client Domain Store에 기록하고, 서버 응답에 따라 Query Cache, pending participation, Realtime Store를 정합시킨다.

`deactivateReadSession`, `unsubscribeRoom`, `clearActiveChatRoomId`는 화면 unmount cleanup에서도 호출될 수 있으므로 같은 room에 반복 적용해도 결과가 달라지지 않아야 한다.

```js
function useLeaveRoomCommand(roomId) {
  const mutation = useMutation({
    mutationFn: () => leaveRoom(roomId),

    onMutate: () => {
      // 사용자의 나가기 요청을 클라이언트 도메인 상태에 기록한다.
      putPendingParticipation({
        roomId,
        action: "LEAVE",
        status: "PENDING",
        requestedAt: now(),
      });
    },

    onSuccess: (response) => {
      // 서버 응답으로 외부 상태를 보정한다.
      reconcileRoomDetail(roomId, response);

      // 나가기 요청이 서버 상태와 정합되었으므로 제거한다.
      removePendingParticipation(roomId, "LEAVE");

      // 더 이상 유지하지 않을 realtime 상태를 정리한다.
      deactivateReadSession(roomId);
      unsubscribeRoom(roomId);
      clearActiveChatRoomId(roomId);
    },

    onError: (error) => {
      // 사용자가 요청했던 나가기 행동의 실패 상태를 유지한다.
      failPendingParticipation(
        roomId,
        "LEAVE",
        error,
      );
    },
  });

  return {
    status: mutation.status,
    error: mutation.error,
    execute: mutation.mutate,
    retry: mutation.mutate,
  };
}
```

### 5.4 sendMessage 실행과 상태 반영

`useSendMessageCommand`는 Mutation Cache의 요청 lifecycle과 Client Domain Store의 `PendingMessage`를 연결한다. command response와 `MESSAGE_SENT` event가 같은 메시지를 각각 확정할 수 있으므로 confirmed message 보정과 pending message 제거는 idempotent하게 수행한다.

```js
function useSendMessageCommand(roomId) {
  const currentUser = useCurrentUser();

  // useMutation이 실행 상태를 Mutation Cache에 기록한다.
  const mutation = useMutation({
    mutationFn: requestSendMessage,

    onMutate: ({ clientMessageId, content }) => {
      // Client Domain Store에 전송 요청을 기록한다.
      putPendingMessage({
        clientMessageId,
        roomId,
        sender: currentUser,
        content,
        status: "SENDING",
        createdAt: now(),
      });
    },

    onSuccess: (message) => {
      // 서버 응답으로 Query Cache를 보정한다.
      mergeConfirmedMessage(message);

      // 서버 상태와 정합된 pending message를 제거한다.
      removePendingMessage(message.clientMessageId);
    },

    onError: (error, { clientMessageId }) => {
      // 사용자에게 유지할 전송 실패 상태를 기록한다.
      failPendingMessage(clientMessageId, error);
    },
  });

  return {
    status: mutation.status,
    error: mutation.error,
    execute: ({ content }) => {
      mutation.mutate({
        clientMessageId: createClientMessageId(),
        roomId,
        content,
      });
    },
  };
}

function useRetryMessageCommand(
  roomId,
  clientMessageId,
) {
  const pendingMessage = usePendingMessage(
    roomId,
    clientMessageId,
  );

  const mutation = useMutation({
    mutationFn: () =>
      requestSendMessage({
        clientMessageId,
        roomId,
        content: pendingMessage.content,
      }),

    onMutate: () => {
      // 기존 pending message를 유지하고 상태만 보정한다.
      retryPendingMessage(clientMessageId);
    },

    onSuccess: (message) => {
      mergeConfirmedMessage(message);
      removePendingMessage(message.clientMessageId);
    },

    onError: (error) => {
      failPendingMessage(clientMessageId, error);
    },
  });

  const canExecute =
    pendingMessage?.status === "FAILED";

  return {
    status: mutation.status,
    error: mutation.error,
    canExecute,
    execute: () => {
      if (canExecute) {
        mutation.mutate();
      }
    },
  };
}

function onMessageSentEvent(message) {
  // Query Cache
  mergeConfirmedMessage(message);

  // Client Domain Store
  removePendingMessage(message.clientMessageId);

  // Realtime Store
  advanceLastReceivedSequence(
    message.roomId,
    message.roomSequence,
  );
}
```

### 5.5 realtime 재연결과 catch-up

active chat room 변경은 `onActiveChatRoomChanged`로, WebSocket 연결 성공은 `onRealtimeConnected`로 전달한다. 두 event는 현재 room 하나를 같은 realtime 복구 흐름으로 연결한다.

연결 중 event sequence gap이 감지되면 `onRoomEventGapDetected`가 `catchUpRequired`를 기록하고, 현재 연결과 subscription이 유효하면 즉시 같은 catch-up 흐름을 실행한다.

`catchUpRoom`은 React 외부의 realtime event handler에서 실행되므로 Hook 대신 `QueryClient`로 query lifecycle을 시작한다.

```js
function onRealtimeDisconnected() {
  setRealtimeConnection("RECONNECTING");

  const roomId = getActiveChatRoomId();
  if (roomId != null) {
    markCatchUpRequired(roomId);
  }

  // Query Cache의 room과 message는 유지한다.
}

async function onRealtimeConnected(queryClient) {
  setRealtimeConnection("CONNECTED");

  const roomId = getActiveChatRoomId();
  if (roomId == null) {
    return;
  }

  await restoreRoomRealtime(queryClient, roomId);
}

async function onActiveChatRoomChanged(
  queryClient,
  roomId,
) {
  const connection = getRealtimeConnection();
  if (connection.status !== "CONNECTED") {
    return;
  }

  await restoreRoomRealtime(queryClient, roomId);
}

async function restoreRoomRealtime(
  queryClient,
  roomId,
) {
  // 각 command는 대응하는 ClientRoomRealtimeCapability를 확인한다.
  await subscribeRoom(roomId);

  const subscription =
    getRoomSubscription(roomId);
  if (subscription.status !== "SUBSCRIBED") {
    return;
  }

  activateReadSession(roomId);

  await catchUpRoomIfRequired(
    queryClient,
    roomId,
  );
}

async function onRoomEventGapDetected(
  queryClient,
  roomId,
) {
  markCatchUpRequired(roomId);

  const connection = getRealtimeConnection();
  if (
    connection.status !== "CONNECTED" ||
    getActiveChatRoomId() !== roomId
  ) {
    return;
  }

  await catchUpRoomIfRequired(
    queryClient,
    roomId,
  );
}

async function catchUpRoomIfRequired(
  queryClient,
  roomId,
) {
  const subscription =
    getRoomSubscription(roomId);
  if (
    subscription.status !== "SUBSCRIBED" ||
    !subscription.catchUpRequired
  ) {
    return;
  }

  await catchUpRoom({
    queryClient,
    roomId,
    afterSequence:
      subscription.lastReceivedSequence,
  });
}

async function catchUpRoom({
  queryClient,
  roomId,
  afterSequence,
}) {
  const result = await queryClient.fetchQuery(
    createCatchUpMessagesQuery({
      roomId,
      afterSequence,
    }),
  );

  // 성공한 catch-up 결과를 confirmed message cache에 병합한다.
  mergeConfirmedMessages(result.messages);

  // 성공한 경우에만 gap 상태와 마지막 수신 sequence를 보정한다.
  // 실패하면 Query Cache에 error가 남고 catchUpRequired는 유지된다.
  clearCatchUpRequired(roomId);
  advanceLastReceivedSequence(
    roomId,
    result.lastSequence,
  );
}
```

### 5.6 렌더링 의존성

```text
ChatPage
  <- roomId

RoomHeader
  <- room detail query state
  <- PendingParticipationAction(LEAVE)

ChatAvailabilityBoundary
  <- room detail query state

ConnectionNotice
  <- realtime connection
  <- 해당 room의 subscription

MessageTimeline
  <- confirmed message query state
  <- 해당 room의 PendingMessage 목록

ConfirmedMessageItem(messageId)
  <- message 표시 값
  <- 현재 사용자
  <- room detail query state
  <- 해당 message의 editMessage command 상태
  <- 해당 message의 deleteMessage command 상태

PendingMessageItem(clientMessageId)
  <- 해당 PendingMessage
  <- 해당 message의 retryMessage command 상태

MessageReadReceipt(roomId, messageId)
  <- 해당 message의 read receipt 선택 결과

MessageComposer
  <- draft Component State
  <- 현재 사용자
  <- room detail query state
  <- PendingParticipationAction(LEAVE)
```
