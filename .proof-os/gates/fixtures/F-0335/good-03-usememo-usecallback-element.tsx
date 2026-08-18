// KNOWN-GOOD 03 — useMemo/useCallback producing ELEMENTS (and a memoised component) inside a
// render body. None of these mint a fresh component identity per render, and all of them are
// everyday React. A detector that reds here gets switched off within a week.
import React from 'react';

const Card = ({ children }: { children: React.ReactNode }) => <div>{children}</div>;

export default function DashboardPage() {
  const [n, setN] = React.useState(0);

  // an ELEMENT held in a memo, rendered as a value, not mounted as a tag
  const header = React.useMemo(() => <h1>Dashboard {n}</h1>, [n]);

  // a callback that RETURNS an element, invoked as a plain call
  const renderFooter = React.useCallback(() => <footer>{n}</footer>, [n]);

  // a memoised component binding — stable identity across renders, so no remount
  const Panel = React.useMemo(() => () => <section><input defaultValue="" /></section>, []);

  const Badge = React.memo(function Badge() {
    return <b>badge</b>;
  });

  return (
    <div>
      {header}
      <button onClick={() => setN(n + 1)}>bump</button>
      <Card>{renderFooter()}</Card>
      <Panel />
      <Badge />
    </div>
  );
}
