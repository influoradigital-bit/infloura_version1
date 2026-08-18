// KNOWN-GOOD 02 — the shape the real page actually uses today: lowercase render HELPERS
// invoked as plain calls. No separate fiber identity is created, so no remount. This is the
// fixed shape F-0050 closed on, and the gate must stay green on it.
import React from 'react';

export default function BrandPipelinePage() {
  const [viewMode, setViewMode] = React.useState<'board' | 'list' | 'timeline'>('board');

  const boardView = () => (
    <div className="board">
      <input defaultValue="" />
    </div>
  );

  const listView = () => (
    <div className="list">
      <input defaultValue="" />
    </div>
  );

  const timelineView = () => (
    <div className="timeline">
      <input defaultValue="" />
    </div>
  );

  return (
    <div>
      <button onClick={() => setViewMode('list')}>switch</button>
      {viewMode === 'board' && boardView()}
      {viewMode === 'list' && listView()}
      {viewMode === 'timeline' && timelineView()}
    </div>
  );
}
