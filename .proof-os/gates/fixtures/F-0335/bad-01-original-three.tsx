// KNOWN-BAD 01 — F-0050 verbatim: the three identifiers the original record named,
// defined inside the page's render body and mounted as JSX.
import React from 'react';

export default function BrandPipelinePage() {
  const [viewMode, setViewMode] = React.useState<'board' | 'list' | 'timeline'>('board');

  const BoardView = () => (
    <div className="board">
      <input defaultValue="" />
    </div>
  );

  const ListView = () => (
    <div className="list">
      <input defaultValue="" />
    </div>
  );

  const TimelineView = () => (
    <div className="timeline">
      <input defaultValue="" />
    </div>
  );

  return (
    <div>
      <button onClick={() => setViewMode('list')}>list</button>
      {viewMode === 'board' && <BoardView />}
      {viewMode === 'list' && <ListView />}
      {viewMode === 'timeline' && <TimelineView />}
    </div>
  );
}
