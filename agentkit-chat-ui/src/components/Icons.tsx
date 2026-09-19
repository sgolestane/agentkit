/**
 * The console's few icons: 20px line drawings in the current text colour, so they follow the
 * design system's tokens like text does. Decorative — every control that shows one also has a
 * label.
 */
function Icon({ children, size = 20 }: { children: React.ReactNode; size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      className="shrink-0"
    >
      {children}
    </svg>
  )
}

/** A pencil on a page: start something new. */
export function NewIcon() {
  return (
    <Icon>
      <path d="M12 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-6" />
      <path d="M17.5 3.5a2.1 2.1 0 0 1 3 3L12 15l-4 1 1-4 8.5-8.5z" />
    </Icon>
  )
}

export function SearchIcon() {
  return (
    <Icon>
      <circle cx="11" cy="11" r="6.5" />
      <path d="m20 20-4.2-4.2" />
    </Icon>
  )
}

/** A panel with its left edge ruled: open or close the sidebar. */
export function SidebarIcon() {
  return (
    <Icon>
      <rect x="3.5" y="4.5" width="17" height="15" rx="3" />
      <path d="M9.5 4.5v15" />
    </Icon>
  )
}

/** A spark: an agent. */
export function AgentIcon() {
  return (
    <Icon>
      <path d="M12 3.5 13.8 9a2 2 0 0 0 1.2 1.2l5.5 1.8-5.5 1.8a2 2 0 0 0-1.2 1.2L12 20.5 10.2 15A2 2 0 0 0 9 13.8L3.5 12 9 10.2A2 2 0 0 0 10.2 9L12 3.5z" />
    </Icon>
  )
}

/** A shield: the organization's admin view. */
export function AdminIcon() {
  return (
    <Icon>
      <path d="M12 3.5 5 6v5.5c0 4.2 2.9 7.6 7 9 4.1-1.4 7-4.8 7-9V6l-7-2.5z" />
      <path d="m9.5 12 1.8 1.8 3.4-3.6" />
    </Icon>
  )
}

export function SignOutIcon() {
  return (
    <Icon>
      <path d="M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4" />
      <path d="M10 16l-4-4 4-4" />
      <path d="M6 12h10" />
    </Icon>
  )
}

/** A speech bubble: back to the conversations. */
export function ChatIcon() {
  return (
    <Icon>
      <path d="M20 12a8 8 0 0 1-11.6 7.1L4 20l1-4.1A8 8 0 1 1 20 12z" />
    </Icon>
  )
}

/** A tick in a circle: a pull request's rehearsal. */
export function RehearsalIcon() {
  return (
    <Icon>
      <circle cx="12" cy="12" r="8.5" />
      <path d="m8.5 12.2 2.4 2.4 4.6-4.9" />
    </Icon>
  )
}

/** A clock: work scheduled for later. */
export function ClockIcon() {
  return (
    <Icon>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.5V12l3 2" />
    </Icon>
  )
}

/** Bars: what was spent. */
export function ChartIcon() {
  return (
    <Icon>
      <path d="M5 20V11M12 20V5M19 20v-6" />
    </Icon>
  )
}

/** A plug: the connectors. */
export function PlugIcon() {
  return (
    <Icon>
      <path d="M9 3.5V8M15 3.5V8M6.5 8h11v3a5.5 5.5 0 0 1-11 0V8zM12 16.5v4" />
    </Icon>
  )
}
