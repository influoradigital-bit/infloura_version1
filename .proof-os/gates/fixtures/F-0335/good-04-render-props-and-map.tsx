// KNOWN-GOOD 04 — render props and map callbacks. Both are functions declared inside a render
// body that RETURN JSX, and both are perfectly correct: the child invokes them as functions, so
// no element type is ever created from them and nothing remounts.
import React from 'react';

const List = ({
  items,
  renderItem,
  children,
}: {
  items: string[];
  renderItem: (s: string) => React.ReactNode;
  children?: (s: string) => React.ReactNode;
}) => (
  <ul>
    {items.map((i) => (
      <React.Fragment key={i}>{renderItem(i)}</React.Fragment>
    ))}
    {children ? children(items[0]) : null}
  </ul>
);

export default function InboxPage() {
  const [q, setQ] = React.useState('');
  const items = ['a', 'b', 'c'];

  // a named render prop, declared in the render body, handed to a child
  const renderItem = (s: string) => <li>{s}</li>;

  return (
    <div>
      <input value={q} onChange={(e) => setQ(e.target.value)} />
      <List items={items} renderItem={renderItem}>
        {(s) => <em>{s}</em>}
      </List>
      {items.map((s) => (
        <span key={s}>{s}</span>
      ))}
    </div>
  );
}
