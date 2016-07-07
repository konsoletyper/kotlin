var sample = function (Kotlin) {
  'use strict';
  var _ = Kotlin.defineRootPackage(null, /** @lends _ */ {
    box: function () {
      Kotlin.println('Hello, world!');
      return 'OK';
    }
  });
  Kotlin.defineModule('sample', _);
  return _;
}(kotlin);
