'use client'

// Inspired by react-hot-toast library
import * as React from 'react'

import type { ToastActionElement, ToastProps } from '@/components/ui/toast'

/**
 * How many toasts may be on screen at once.
 *
 * CR-03: this was 1, which made the queue a single slot with no queueing — any
 * second toast fired in the same moment replaced the first outright, so whichever
 * of two simultaneous failures happened to dispatch last was the only one the
 * user ever saw.
 *
 * 2, not 3: the real burst this needs to survive is exactly two deep (a failed
 * action plus the refetch it triggers). Each toast is `p-6`, and the viewport is
 * pinned to `top-0` on mobile, so every extra slot is ~100px of a phone screen
 * covered — 3 could blanket the header. 2 clears the actual requirement at half
 * the worst-case footprint. Paired with `gap-2` + `max-h-[60vh]` on ToastViewport
 * (components/ui/toast.tsx), which had no gap and no realistic height cap because
 * a stack was impossible while this was 1.
 */
const TOAST_LIMIT = 2

/**
 * How long a toast stays in the store AFTER it has been dismissed/closed, before
 * being dropped from state. This is purely the exit-animation grace period.
 *
 * CR-03: this was 1000000 (16.6 minutes) — the shadcn default that nobody ever
 * changes. Combined with TOAST_LIMIT above it meant a toast the user closed at
 * 09:00 was still occupying the store at 09:16, so `dismiss()` with no id and any
 * capacity arithmetic operated on long-dead entries. 4s comfortably outlasts the
 * Radix slide-out.
 */
const TOAST_REMOVE_DELAY = 4000

type ToasterToast = ToastProps & {
  id: string
  title?: React.ReactNode
  description?: React.ReactNode
  action?: ToastActionElement
}

let count = 0

function genId() {
  count = (count + 1) % Number.MAX_SAFE_INTEGER
  return count.toString()
}

type ActionType = {
  ADD_TOAST: 'ADD_TOAST'
  UPDATE_TOAST: 'UPDATE_TOAST'
  DISMISS_TOAST: 'DISMISS_TOAST'
  REMOVE_TOAST: 'REMOVE_TOAST'
}

type Action =
  | {
      type: ActionType['ADD_TOAST']
      toast: ToasterToast
    }
  | {
      type: ActionType['UPDATE_TOAST']
      toast: Partial<ToasterToast>
    }
  | {
      type: ActionType['DISMISS_TOAST']
      toastId?: ToasterToast['id']
    }
  | {
      type: ActionType['REMOVE_TOAST']
      toastId?: ToasterToast['id']
    }

interface State {
  toasts: ToasterToast[]
}

const toastTimeouts = new Map<string, ReturnType<typeof setTimeout>>()

const addToRemoveQueue = (toastId: string) => {
  if (toastTimeouts.has(toastId)) {
    return
  }

  const timeout = setTimeout(() => {
    toastTimeouts.delete(toastId)
    dispatch({
      type: 'REMOVE_TOAST',
      toastId: toastId,
    })
  }, TOAST_REMOVE_DELAY)

  toastTimeouts.set(toastId, timeout)
}

export const reducer = (state: State, action: Action): State => {
  switch (action.type) {
    case 'ADD_TOAST':
      return {
        ...state,
        toasts: [action.toast, ...state.toasts].slice(0, TOAST_LIMIT),
      }

    case 'UPDATE_TOAST':
      return {
        ...state,
        toasts: state.toasts.map((t) =>
          t.id === action.toast.id ? { ...t, ...action.toast } : t,
        ),
      }

    case 'DISMISS_TOAST': {
      const { toastId } = action

      // ! Side effects ! - This could be extracted into a dismissToast() action,
      // but I'll keep it here for simplicity
      if (toastId) {
        addToRemoveQueue(toastId)
      } else {
        state.toasts.forEach((toast) => {
          addToRemoveQueue(toast.id)
        })
      }

      return {
        ...state,
        toasts: state.toasts.map((t) =>
          t.id === toastId || toastId === undefined
            ? {
                ...t,
                open: false,
              }
            : t,
        ),
      }
    }
    case 'REMOVE_TOAST':
      if (action.toastId === undefined) {
        return {
          ...state,
          toasts: [],
        }
      }
      return {
        ...state,
        toasts: state.toasts.filter((t) => t.id !== action.toastId),
      }
  }
}

const listeners: Array<() => void> = []

let memoryState: State = { toasts: [] }

function dispatch(action: Action) {
  memoryState = reducer(memoryState, action)
  listeners.forEach((listener) => {
    listener()
  })
}

/** `useSyncExternalStore` subscribe half — returns its own unsubscribe. */
function subscribe(onStoreChange: () => void) {
  listeners.push(onStoreChange)
  return () => {
    const index = listeners.indexOf(onStoreChange)
    if (index > -1) {
      listeners.splice(index, 1)
    }
  }
}

/** `useSyncExternalStore` snapshot half. `memoryState` is replaced wholesale by
 *  the reducer on every dispatch and otherwise referentially stable, which is
 *  exactly the identity contract the hook requires. */
function getSnapshot() {
  return memoryState
}

type Toast = Omit<ToasterToast, 'id'>

function toast({ ...props }: Toast) {
  const id = genId()

  const update = (props: ToasterToast) =>
    dispatch({
      type: 'UPDATE_TOAST',
      toast: { ...props, id },
    })
  const dismiss = () => dispatch({ type: 'DISMISS_TOAST', toastId: id })

  dispatch({
    type: 'ADD_TOAST',
    toast: {
      ...props,
      id,
      open: true,
      onOpenChange: (open) => {
        if (!open) dismiss()
      },
    },
  })

  return {
    id: id,
    dismiss,
    update,
  }
}

/**
 * CR-03: this was a hand-rolled `useState(memoryState)` + `useEffect(..., [state])`
 * subscription — the shadcn original. Two problems, both of which drop toasts on
 * the floor rather than showing them:
 *
 *   1. The initial value is snapshotted at FIRST render but the listener is only
 *      registered on commit. Anything dispatched in that gap mutated the module
 *      store while nobody was subscribed, and never reached the screen.
 *   2. `[state]` as the dependency tore the listener down and re-registered it on
 *      every single toast, reopening that same gap on each update.
 *
 * `useSyncExternalStore` is the purpose-built primitive for exactly this shape:
 * it re-reads the snapshot on subscribe, so neither gap can exist, and it is
 * tear-safe under concurrent rendering.
 */
function useToast() {
  const state = React.useSyncExternalStore(subscribe, getSnapshot, getSnapshot)

  return {
    ...state,
    toast,
    dismiss: (toastId?: string) => dispatch({ type: 'DISMISS_TOAST', toastId }),
  }
}

export { useToast, toast }
