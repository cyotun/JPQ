// No workers.dev URL or public route. Only the JPQ update service binds this worker.
export default { fetch(request,env) { return env.ASSETS.fetch(request); } };
