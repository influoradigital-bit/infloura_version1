import React from 'react';

export default class ErrorBoundary extends React.Component {
  render() {
    if (this.state.hasError) {
      return (
        <div>
          <h1>Something went wrong</h1>
          <button>Retry</button>
          <button>Reload page</button>
        </div>
      );
    }
    return this.props.children;
  }
}
