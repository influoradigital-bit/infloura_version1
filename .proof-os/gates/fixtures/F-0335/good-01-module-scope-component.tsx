// KNOWN-GOOD 01 — the single most ordinary thing in React, and the fix record F-0050 itself
// recommends ("hoist to module scope threading closed-over state as props"). A class-detector
// that reds here would fail the prescribed fix, so this fixture is load-bearing.
import React from 'react';

const BoardView = ({ items }: { items: string[] }) => (
  <div className="board">
    {items.map((i) => (
      <span key={i}>{i}</span>
    ))}
  </div>
);

function ListView({ items }: { items: string[] }) {
  return <ul>{items.map((i) => <li key={i}>{i}</li>)}</ul>;
}

class TimelineView extends React.Component<{ items: string[] }> {
  render() {
    return <ol>{this.props.items.length}</ol>;
  }
}

export default function PipelinePage() {
  const [viewMode, setViewMode] = React.useState('board');
  const items = ['a', 'b'];
  return (
    <div>
      <button onClick={() => setViewMode('list')}>switch</button>
      {viewMode === 'board' && <BoardView items={items} />}
      {viewMode === 'list' && <ListView items={items} />}
      {viewMode === 'timeline' && <TimelineView items={items} />}
    </div>
  );
}
