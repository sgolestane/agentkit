import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../lib/api'
import { isStale, stampInThisPage } from '../lib/build'
import { useConversation } from '../lib/useConversation'
import { useUploads } from '../lib/useUploads'
import { useThreads } from '../lib/useThreads'
import { Threads } from './Threads'
import { money } from './Trace'
import type { Overview } from '../lib/types'
import { Composer } from './Composer'
import { TaskForm } from './TaskForm'
import { Transcript } from './Transcript'

/**
 * The console.
 *
 * <h4>Built against threads from the first line</h4>
 *
 * There is a conversation list here even though creating, renaming and deleting them is #349's
 * card. That is deliberate: a shell written around "the conversation" grows a global everywhere,
 * and retrofitting a second one means touching every component that assumed there was one. The
 * id is a parameter from the start; #349 adds the management around it.
 */
export function Console() {
  const [overview, setOverview] = useState<Overview | null>(null)
  const [bootProblem, setBootProblem] = useState<string | null>(null)
  const [draft, setDraft] = useState('')

  const threads = useThreads()
  const conversation = useConversation(threads.current)
  const uploads = useUploads(threads.current)

  useEffect(() => {
    let live = true
    api
      .overview()
      .then((read) => {
        if (!live) {
          return
        }
        // A tab left open across a restart is running yesterday's JavaScript against today's
        // API, and the failures that produces read as bugs rather than as staleness.
        if (isStale(stampInThisPage(), read.build)) {
          window.location.reload()
          return
        }
        setOverview(read)
      })
      .catch((error: unknown) => {
        if (live) {
          setBootProblem(error instanceof ApiError ? error.message : 'The console did not answer.')
        }
      })
    return () => {
      live = false
    }
  }, [])

  // The server names a conversation after the first thing said in it, so the list learns the
  // title without a second request.
  //
  // Depending on `threads.noteTitle` and `threads.current` rather than on `threads`: the hook
  // returns a fresh object every render, so the whole object as a dependency means this runs
  // after every render — and with a setter that returned a new array each time, that was an
  // infinite loop.
  const { current: openThread, noteTitle } = threads
  useEffect(() => {
    if (openThread && conversation.title) {
      noteTitle(openThread, conversation.title)
    }
  }, [openThread, noteTitle, conversation.title])

  const send = useCallback(
    (text: string) => {
      setDraft('')
      // Only the files that actually arrived travel with the message. One that failed stays
      // in the composer with its reason on it, so the person can see what did not go.
      void conversation.say(text, uploads.ready)
      uploads.clear()
    },
    [conversation, uploads],
  )

  // The open conversation's agent, when the console offers more than one: its form, if it has one.
  const openAgentId = threads.threads.find((one) => one.id === threads.current)?.agent?.id
  const openAgent = overview?.agents?.find((agent) => agent.id === openAgentId)

  const problems = overview?.problems ?? []
  // Disabled with a reason rather than disabled: a person whose console will not accept a
  // message is owed the sentence that says why, and it is already written — the runtime's own.
  const unusable = bootProblem ?? threads.problem ?? (problems.length > 0 ? problems.join(' ') : null)

  return (
    <div className="flex h-full">
      <Threads
        threads={threads.shown}
        current={threads.current}
        filter={threads.filter}
        working={conversation.working}
        onFilter={threads.setFilter}
        onOpen={threads.open}
        agents={overview?.agents ?? []}
        onCreate={(agent) => void threads.create(agent)}
        onRename={(id, title) => void threads.rename(id, title)}
        onForget={(id) => void threads.forget(id)}
      />

      <main className="flex min-w-0 flex-1 flex-col">
        {unusable ? (
          <p className="border-b border-warn bg-panel px-4 py-2 text-sm text-warn" role="alert">
            {unusable}
          </p>
        ) : null}
        {conversation.problem ? (
          <p className="border-b border-bad bg-panel px-4 py-2 text-sm text-bad" role="alert">
            {conversation.problem}
          </p>
        ) : null}
        {conversation.reconnecting ? (
          <p className="border-b border-line bg-panel px-4 py-1 text-xs text-muted" role="status">
            Reconnecting to the run…
          </p>
        ) : null}
        {conversation.spent.tokens > 0 ? (
          <p className="border-b border-line bg-panel px-4 py-1 text-right text-xs text-muted" data-testid="spent">
            {conversation.spent.tokens.toLocaleString()} tokens
            {typeof conversation.spent.costUsd === 'number'
              ? ` · ${money(conversation.spent.costUsd)}`
              : ''}
          </p>
        ) : null}

        <Transcript
          turns={conversation.transcript.turns}
          working={conversation.working}
          runningTool={conversation.runningTool}
          pending={conversation.pending}
          attachments={conversation.attachments}
          onStop={() => void conversation.stop()}
          onDecide={(id, verdict, body) => void conversation.decide(id, verdict, body)}
          onRegenerate={() => {
            const last = conversation.transcript.turns[conversation.transcript.turns.length - 1]
            if (last?.userText) {
              void conversation.say(last.userText)
            }
          }}
          onEdit={setDraft}
        />

        {openAgent?.input && threads.current ? (
          <TaskForm
            key={threads.current}
            schema={openAgent.input}
            agentName={openAgent.name}
            startOpen={conversation.transcript.turns.length === 0}
            disabled={Boolean(unusable) || conversation.working}
            onSubmit={(input) => void conversation.say('', [], input)}
          />
        ) : null}

        <Composer
          onSend={send}
          initialText={draft}
          disabled={Boolean(unusable) || !threads.current}
          disabledReason={unusable ?? 'No conversation is open.'}
          uploads={uploads.uploads}
          onAttach={(files) => void uploads.add(files)}
          onRemoveUpload={uploads.remove}
        />
      </main>
    </div>
  )
}
