/**
 * Created by Jeong on 2016-03-21.
 */
'use strict';

angular.module('testApp')
    .directive('krUpdate', function () {
        return {
            require: 'ngModel',
            restrict: 'A',
            link: function (scope, element, attrs, ngModel) {
                var blank_pattern = /^\s+|\s+$/g;

                element.keyup(function () {

                    if (element.val().length > 0) {
                        if (element.val().replace(blank_pattern, '') == "") {
                            element.val("");
                            return false;
                        }
                    }

                    ngModel.$setViewValue(element.val());
                    scope.$apply();
                });

	            console.log("어떤게 변한지 알수있을까??");

                element.blur(function () {
                    if (element.val().length > 0) {
                        if (element.val().replace(blank_pattern, '') == "") {
                            element.val("");
                            return false;
                        }
                    }

                    ngModel.$setViewValue(element.val());
                    scope.$apply();
                });

                element.keydown(function () {
                    if (element.val().length > 0) {
                        if (element.val().replace(blank_pattern, '') == "") {
                            element.val("");
                            return false;
                        }
                    }

                    ngModel.$setViewValue(element.val());
                    scope.$apply();
                });
            }
        }
    });