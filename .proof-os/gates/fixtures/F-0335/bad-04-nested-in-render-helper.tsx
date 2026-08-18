// KNOWN-BAD 04 — buried one level deeper: declared inside a render HELPER that is itself
// inside the component. The helper is called during render, so the identity is still fresh
// every time. A detector that only looks one level down misses this.
import React from 'react';

export default function DealRoomPage() {
  const [open, setOpen] = React.useState(false);

  const bodyView = () => {
    const AttachmentRow = function () {
      return (
        <li>
          <input defaultValue="" />
        </li>
      );
    };
    return (
      <ul>
        <AttachmentRow />
      </ul>
    );
  };

  return (
    <div>
      <button onClick={() => setOpen(!open)}>toggle</button>
      {bodyView()}
    </div>
  );
}
