namespace VrcxCompanion.Core.Sync;

/// <summary>An auto-reset event for async waiters (one waiter at a time).</summary>
internal sealed class AsyncSignal
{
    private readonly object _gate = new();
    private TaskCompletionSource? _waiter;
    private bool _signaled;

    public void Set()
    {
        TaskCompletionSource? toRelease;
        lock (_gate)
        {
            if (_waiter == null)
            {
                _signaled = true;
                return;
            }
            toRelease = _waiter;
            _waiter = null;
        }
        toRelease.TrySetResult();
    }

    public Task WaitAsync(CancellationToken cancellationToken)
    {
        lock (_gate)
        {
            if (_signaled)
            {
                _signaled = false;
                return Task.CompletedTask;
            }
            _waiter ??= new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            return _waiter.Task.WaitAsync(cancellationToken);
        }
    }
}
