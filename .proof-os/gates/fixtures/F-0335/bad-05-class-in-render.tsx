// KNOWN-BAD 05 — a CLASS component declared in the render body. Same remount, and a class
// loses instance state as well as hook state.
import React from 'react';

export default function ReportPage() {
  const [range, setRange] = React.useState('7d');

  class Chart extends React.Component {
    render() {
      return <canvas />;
    }
  }

  return (
    <div>
      <button onClick={() => setRange('30d')}>{range}</button>
      <Chart />
    </div>
  );
}
